package com.smarthome.energy.bench;

import com.smarthome.energy.db.ConnectionFactory;
import com.smarthome.energy.db.DataAccessException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Evidence 2: what an insert strategy is worth, measured against the real schema.
 *
 * <p>The system's persistence path opens a connection, writes one reading in one transaction,
 * and closes it — once per reading. That is the simplest thing that is correct, and this
 * benchmark is where the cost of that simplicity gets a number rather than a shrug. Three
 * strategies write the same rows to the same table, and each differs from the one above it in
 * exactly one respect, so the table below reads as two isolated effects rather than one
 * confounded comparison:</p>
 *
 * <ol>
 *   <li><strong>Autocommit, one INSERT per row.</strong> A fresh connection and an implicit
 *       transaction per row — what {@code PersistenceSink} does today, run 50,000 times.</li>
 *   <li><strong>PreparedStatement, batched.</strong> Same fresh-connection-per-unit-of-work,
 *       but the unit is a batch of 500 rows in one explicit transaction. The delta from row 1
 *       is the cost of per-row round trips and per-row commits.</li>
 *   <li><strong>Batched and pooled.</strong> Identical batching, connections borrowed from a
 *       {@link ConnectionPool} instead of opened. The delta from row 2 is the cost of opening
 *       a connection: a TCP handshake and a MySQL authentication exchange, per unit.</li>
 * </ol>
 *
 * <pre>
 *   mvn exec:java -Dexec.mainClass=com.smarthome.energy.bench.JdbcBatchBenchmark
 * </pre>
 *
 * <h2>The rows it writes, and getting rid of them</h2>
 *
 * <p>Half a million benchmark readings left in {@code readings} would quietly distort every
 * subsequent measurement — the analytics' peak-hour profile, the index comparison's row
 * counts, the dashboard's history window. So every row this benchmark writes is stamped
 * inside a sentinel time window ({@link #SENTINEL_EPOCH}, in the year 2000, decades before any
 * real reading), and the run deletes exactly that window when it finishes. {@code --keep}
 * skips the cleanup for anyone who wants to inspect what was written.</p>
 *
 * <p>The insert is the DAO's statement, written out here rather than called through
 * {@code ReadingDao}: the batched strategies need {@code addBatch} on a statement they hold
 * across rows, which is not something the DAO's one-reading-per-call API exposes. The SQL is
 * the same, and that it has to be restated is itself part of the finding — the DAO's shape,
 * not the database, is what makes batching unavailable to the running system.</p>
 *
 * <p>Syllabus mapping: Unit III — JDBC (PreparedStatement, batching, transactions, pooling).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class JdbcBatchBenchmark {

    /** Rows written by each strategy when {@code --rows} says nothing. */
    private static final int DEFAULT_ROWS = 50_000;

    /** Rows per {@code executeBatch} when {@code --batch} says nothing. */
    private static final int DEFAULT_BATCH_SIZE = 500;

    /** Connections held by the pooled strategy when {@code --pool} says nothing. */
    private static final int DEFAULT_POOL_SIZE = 4;

    /** Device the benchmark rows are attributed to; must exist in {@code devices}. */
    private static final int DEFAULT_DEVICE_ID = 1;

    /**
     * Start of the sentinel window every benchmark row is stamped in: 2000-01-01T00:00:00Z.
     * Chosen to be decades before any reading the system could legitimately hold, so the
     * cleanup delete cannot take a real row with it.
     */
    private static final Instant SENTINEL_EPOCH = Instant.parse("2000-01-01T00:00:00Z");

    /** End of the sentinel window. One row per millisecond leaves room for ~31 million rows. */
    private static final Instant SENTINEL_END = Instant.parse("2001-01-01T00:00:00Z");

    private static final String SQL_INSERT = """
            INSERT INTO readings (device_id, reading_ts, voltage, current_amp, power_watts)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String SQL_CLEANUP = """
            DELETE FROM readings WHERE reading_ts >= ? AND reading_ts < ?
            """;

    private static final String SQL_DEVICE_EXISTS = """
            SELECT COUNT(*) FROM devices WHERE device_id = ?
            """;

    private JdbcBatchBenchmark() {
        // Entry point only.
    }

    /**
     * Runs the three strategies and prints the results table.
     *
     * @param args {@code --rows N}, {@code --batch N}, {@code --pool N}, {@code --device N},
     *             {@code --keep}, {@code --csv PATH}, {@code --help}
     */
    public static void main(String[] args) {
        int rows = DEFAULT_ROWS;
        int batchSize = DEFAULT_BATCH_SIZE;
        int poolSize = DEFAULT_POOL_SIZE;
        int deviceId = DEFAULT_DEVICE_ID;
        boolean keep = false;
        Path csv = null;

        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--rows" -> rows = intArg(args, ++i);
                    case "--batch" -> batchSize = intArg(args, ++i);
                    case "--pool" -> poolSize = intArg(args, ++i);
                    case "--device" -> deviceId = intArg(args, ++i);
                    case "--keep" -> keep = true;
                    case "--csv" -> csv = Path.of(stringArg(args, ++i));
                    case "--help", "-h" -> {
                        printUsage();
                        return;
                    }
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }
            requirePositive(rows, "--rows");
            requirePositive(batchSize, "--batch");
            requirePositive(poolSize, "--pool");
            requirePositive(deviceId, "--device");
        } catch (IllegalArgumentException e) {
            System.err.println("[bench] " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        ConnectionFactory connections;
        try {
            connections = ConnectionFactory.fromDefaultConfig();
            requireDevice(connections, deviceId);
        } catch (DataAccessException e) {
            System.err.println("[bench] database unavailable: " + e.getMessage());
            System.err.println("[bench] start MySQL with `docker compose up -d` and copy "
                    + "src/main/resources/db.properties.example to db.properties.");
            System.exit(1);
            return;
        }

        System.out.println("[bench] JDBC insert benchmark: rows=" + rows + " batch=" + batchSize
                + " pool=" + poolSize + " device=" + deviceId);
        System.out.println("[bench] target " + connections.getUrl());

        // Effectively-final copies, so the three strategies can be passed as lambdas.
        final int rowCount = rows;
        final int batch = batchSize;
        final int pool = poolSize;
        final int device = deviceId;

        List<Result> results = new ArrayList<>();
        try {
            results.add(time("Autocommit, one INSERT per row", rowCount,
                    () -> insertPerRowAutocommit(connections, device, rowCount)));
            cleanup(connections);

            results.add(time("PreparedStatement, batched (" + batch + ")", rowCount,
                    () -> insertBatched(connections, device, rowCount, batch)));
            cleanup(connections);

            results.add(time("Batched + pooled connection", rowCount,
                    () -> insertBatchedPooled(connections, device, rowCount, batch, pool)));
        } catch (DataAccessException e) {
            System.err.println("[bench] run failed: " + e.getMessage());
            System.exit(1);
            return;
        } finally {
            if (!keep) {
                cleanup(connections);
            } else {
                System.out.println("[bench] --keep: benchmark rows left in the sentinel window "
                        + SENTINEL_EPOCH + " .. " + SENTINEL_END);
            }
        }

        System.out.println();
        printTable(results);
        if (csv != null) {
            writeCsv(csv, results);
        }
    }

    /**
     * Strategy 1: a fresh connection, an implicit transaction, and a round trip per row.
     *
     * <p>This is not a straw man built to lose. It is what {@code ReadingDao.insert(Reading)}
     * does on every call, and what the persistence sink does for every reading that arrives.
     * At the rate six meters actually stream — six readings a second — it is entirely
     * adequate, which is the reason the system still does it. The number this row produces is
     * the headroom, and knowing the headroom is the difference between a defensible choice and
     * a lucky one.</p>
     */
    private static void insertPerRowAutocommit(ConnectionFactory connections, int deviceId, int rows) {
        for (int i = 0; i < rows; i++) {
            try (Connection connection = connections.getConnection();
                 PreparedStatement ps = connection.prepareStatement(SQL_INSERT)) {
                bind(ps, deviceId, i);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new DataAccessException("autocommit insert failed at row " + i, e);
            }
        }
    }

    /** Strategy 2: one connection and one explicit transaction per batch of {@code batchSize}. */
    private static void insertBatched(ConnectionFactory connections, int deviceId, int rows,
                                      int batchSize) {
        int written = 0;
        while (written < rows) {
            int inThisBatch = Math.min(batchSize, rows - written);
            try (Connection connection = connections.getConnection()) {
                writeBatch(connection, deviceId, written, inThisBatch);
            } catch (SQLException e) {
                throw new DataAccessException("batched insert failed at row " + written, e);
            }
            written += inThisBatch;
        }
    }

    /** Strategy 3: identical batching, connections borrowed rather than opened. */
    private static void insertBatchedPooled(ConnectionFactory connections, int deviceId, int rows,
                                            int batchSize, int poolSize) {
        try (ConnectionPool pool = new ConnectionPool(connections, poolSize)) {
            int written = 0;
            while (written < rows) {
                int inThisBatch = Math.min(batchSize, rows - written);
                Connection connection = pool.borrow();
                try {
                    writeBatch(connection, deviceId, written, inThisBatch);
                } catch (SQLException e) {
                    throw new DataAccessException("pooled batched insert failed at row " + written, e);
                } finally {
                    pool.release(connection);
                }
                written += inThisBatch;
            }
        }
    }

    /** Writes one batch in one transaction on a connection the caller owns. */
    private static void writeBatch(Connection connection, int deviceId, int firstRow, int count)
            throws SQLException {
        boolean autoCommitBefore = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(SQL_INSERT)) {
            for (int i = 0; i < count; i++) {
                bind(ps, deviceId, firstRow + i);
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommitBefore);
        }
    }

    /**
     * Binds one row. The timestamp is the sentinel epoch plus the row index in milliseconds,
     * which makes every row distinct, keeps them all inside the window the cleanup deletes,
     * and — because they ascend — writes the index in the order it likes best, so the
     * comparison is not measuring page splits.
     */
    private static void bind(PreparedStatement ps, int deviceId, int rowIndex) throws SQLException {
        ps.setInt(1, deviceId);
        ps.setObject(2, LocalDateTime.ofInstant(SENTINEL_EPOCH.plusMillis(rowIndex), ZoneOffset.UTC));
        ps.setDouble(3, 230.00);
        ps.setDouble(4, 4.35);
        ps.setDouble(5, 1000.50);
    }

    /** Deletes every row in the sentinel window, leaving real readings untouched. */
    private static void cleanup(ConnectionFactory connections) {
        try (Connection connection = connections.getConnection();
             PreparedStatement ps = connection.prepareStatement(SQL_CLEANUP)) {
            ps.setObject(1, LocalDateTime.ofInstant(SENTINEL_EPOCH, ZoneOffset.UTC));
            ps.setObject(2, LocalDateTime.ofInstant(SENTINEL_END, ZoneOffset.UTC));
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                System.out.println("[bench] cleaned up " + String.format(Locale.ROOT, "%,d", deleted)
                        + " benchmark row(s)");
            }
        } catch (SQLException e) {
            System.err.println("[bench] cleanup failed; benchmark rows may remain in the "
                    + "sentinel window: " + e.getMessage());
        }
    }

    /** Fails early and specifically if the device the rows would reference does not exist. */
    private static void requireDevice(ConnectionFactory connections, int deviceId) {
        try (Connection connection = connections.getConnection();
             PreparedStatement ps = connection.prepareStatement(SQL_DEVICE_EXISTS)) {
            ps.setInt(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getLong(1) > 0) {
                    return;
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not check that device " + deviceId + " exists", e);
        }
        throw new DataAccessException("device " + deviceId + " is not in the devices table, so every "
                + "insert would violate the foreign key. Load sql/seed.sql, or name another device "
                + "with --device.");
    }

    /** Times one strategy and returns its row. */
    private static Result time(String name, int rows, Runnable strategy) {
        System.out.println("[bench] " + name + " …");
        long startNanos = System.nanoTime();
        strategy.run();
        double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        Result result = new Result(name, rows, seconds, rows / seconds);
        System.out.printf(Locale.ROOT, "[bench]   %,d rows in %.2f s (%,.0f rows/s)%n",
                rows, seconds, result.rowsPerSecond());
        return result;
    }

    /** One row of the results table. */
    private record Result(String strategy, int rows, double seconds, double rowsPerSecond) {
    }

    /** Prints the results as the markdown table the README's Evidence 2 carries. */
    private static void printTable(List<Result> results) {
        System.out.println("| Strategy | Rows | Wall clock (s) | Rows/s |");
        System.out.println("| -------- | ---- | -------------- | ------ |");
        for (Result r : results) {
            System.out.printf(Locale.ROOT, "| %s | %,d | %.2f | %,.0f |%n",
                    r.strategy(), r.rows(), r.seconds(), r.rowsPerSecond());
        }
        if (results.size() > 1) {
            Result baseline = results.get(0);
            System.out.println();
            for (int i = 1; i < results.size(); i++) {
                Result r = results.get(i);
                System.out.printf(Locale.ROOT, "  %s is %.1fx the baseline%n",
                        r.strategy(), r.rowsPerSecond() / baseline.rowsPerSecond());
            }
        }
    }

    private static void writeCsv(Path path, List<Result> results) {
        StringBuilder csv = new StringBuilder("strategy,rows,seconds,rows_per_second\n");
        for (Result r : results) {
            csv.append(String.format(Locale.ROOT, "\"%s\",%d,%.3f,%.1f%n",
                    r.strategy(), r.rows(), r.seconds(), r.rowsPerSecond()));
        }
        try {
            Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
            System.out.println("[bench] wrote " + results.size() + " row(s) to " + path);
        } catch (IOException e) {
            System.err.println("[bench] could not write " + path + ": " + e.getMessage());
        }
    }

    private static void requirePositive(int value, String option) {
        if (value <= 0) {
            throw new IllegalArgumentException(option + " must be positive, was " + value);
        }
    }

    private static String stringArg(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException("option " + args[index - 1] + " needs a value");
        }
        return args[index];
    }

    private static int intArg(String[] args, int index) {
        try {
            return Integer.parseInt(stringArg(args, index));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("option " + args[index - 1] + " needs a whole number, "
                    + "got '" + args[index] + "'", e);
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage: JdbcBatchBenchmark [options]

                  --rows N      rows each strategy writes (default 50000)
                  --batch N     rows per executeBatch for the batched strategies (default 500)
                  --pool N      connections held by the pooled strategy (default 4)
                  --device N    device id the rows are attributed to (default 1; must exist)
                  --keep        leave the benchmark rows in place instead of deleting them
                  --csv PATH    also write the results as CSV
                  --help        show this message
                """);
    }
}
