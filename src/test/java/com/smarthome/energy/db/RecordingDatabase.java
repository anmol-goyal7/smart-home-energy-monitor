package com.smarthome.energy.db;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * A JDBC driver that records what was asked of it instead of storing anything.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The claim under test is about ordering and atomicity: that a reading and the events it
 * triggered are written on one connection, inside one transaction, and that the alerts reach
 * the dashboard only after the commit. That is a property of the <em>sequence of JDBC calls</em>,
 * not of any row that ends up in MySQL — so it can be tested exactly, on any machine, by
 * recording the sequence. A test that needed a real database would be skipped on every
 * machine that does not have one, which is precisely where regressions hide.</p>
 *
 * <p>Each connection is a dynamic proxy, so this class implements the three or four methods
 * that matter and lets the several hundred others on {@code Connection},
 * {@code PreparedStatement}, and {@code ResultSet} return harmless defaults. Writing them out
 * by hand would be two thousand lines of noise around the six that carry the meaning.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class RecordingDatabase implements Driver {

    /** URL prefix this driver answers to. */
    public static final String URL = "jdbc:recording://in-memory";

    private static final RecordingDatabase INSTANCE = new RecordingDatabase();

    private static final List<String> CALLS = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicLong NEXT_KEY = new AtomicLong(1_000L);
    private static final AtomicInteger OPEN_CONNECTIONS = new AtomicInteger();

    /** SQL fragment whose {@code executeUpdate} should fail, or null for a healthy database. */
    private static volatile String failOn;

    static {
        try {
            DriverManager.registerDriver(INSTANCE);
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private RecordingDatabase() {
        // Registered once, statically.
    }

    /** Clears the recording and any injected failure; call from a test's set-up. */
    public static void reset() {
        CALLS.clear();
        failOn = null;
        OPEN_CONNECTIONS.set(0);
        NEXT_KEY.set(1_000L);
    }

    /** @return the JDBC calls made since {@link #reset()}, in order. */
    public static List<String> calls() {
        synchronized (CALLS) {
            return List.copyOf(CALLS);
        }
    }

    /** @return connections opened but not yet closed. */
    public static int openConnections() {
        return OPEN_CONNECTIONS.get();
    }

    /**
     * Makes the next statement whose SQL contains {@code fragment} fail on execution, which is
     * how the rollback path is exercised.
     *
     * @param fragment a fragment of the SQL to fail, e.g. {@code "INSERT INTO events"}
     */
    public static void failOn(String fragment) {
        failOn = fragment;
    }

    /** @return a factory pointed at this driver. */
    public static ConnectionFactory connectionFactory() {
        return new ConnectionFactory(URL, "test", "test");
    }

    private static void record(String call) {
        CALLS.add(call);
    }

    /** Names the statement by the table it touches, which is all the assertions care about. */
    private static String describe(String sql) {
        String flattened = sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (flattened.startsWith("insert into readings")) {
            return "insert readings";
        }
        if (flattened.startsWith("insert into events")) {
            return "insert events";
        }
        return flattened.length() > 40 ? flattened.substring(0, 40) : flattened;
    }

    // ------------------------------------------------------------------ Driver

    @Override
    public Connection connect(String url, Properties info) {
        if (!acceptsURL(url)) {
            return null;
        }
        OPEN_CONNECTIONS.incrementAndGet();
        record("open");
        return proxy(Connection.class, new ConnectionHandler());
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger(RecordingDatabase.class.getName());
    }

    // ------------------------------------------------------------------ the proxies

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(RecordingDatabase.class.getClassLoader(),
                new Class<?>[] {type}, handler);
    }

    /** Records the transaction-shaped calls and hands out statement proxies. */
    private static final class ConnectionHandler implements InvocationHandler {

        @Override
        public Object invoke(Object self, Method method, Object[] args) {
            switch (method.getName()) {
                case "setAutoCommit" -> {
                    record("autoCommit=" + args[0]);
                    return null;
                }
                case "getAutoCommit" -> {
                    return Boolean.TRUE;
                }
                case "prepareStatement" -> {
                    return proxy(PreparedStatement.class,
                            new StatementHandler(String.valueOf(args[0])));
                }
                case "commit" -> {
                    record("commit");
                    return null;
                }
                case "rollback" -> {
                    record("rollback");
                    return null;
                }
                case "close" -> {
                    OPEN_CONNECTIONS.decrementAndGet();
                    record("close");
                    return null;
                }
                case "toString" -> {
                    return "RecordingConnection";
                }
                default -> {
                    return defaultValue(method.getReturnType());
                }
            }
        }
    }

    /** Records the executed statement and returns a generated key for it. */
    private static final class StatementHandler implements InvocationHandler {

        private final String sql;
        private long generatedKey;

        StatementHandler(String sql) {
            this.sql = sql;
        }

        @Override
        public Object invoke(Object self, Method method, Object[] args) throws SQLException {
            switch (method.getName()) {
                case "executeUpdate" -> {
                    String injected = failOn;
                    if (injected != null && sql.contains(injected)) {
                        record("failed " + describe(sql));
                        throw new SQLException("injected failure on " + describe(sql));
                    }
                    record(describe(sql));
                    generatedKey = NEXT_KEY.incrementAndGet();
                    return 1;
                }
                case "getGeneratedKeys" -> {
                    return proxy(ResultSet.class, new KeysHandler(generatedKey));
                }
                case "toString" -> {
                    return "RecordingStatement[" + describe(sql) + "]";
                }
                default -> {
                    return defaultValue(method.getReturnType());
                }
            }
        }
    }

    /** A one-row result set carrying the key the insert "generated". */
    private static final class KeysHandler implements InvocationHandler {

        private final long key;
        private boolean consumed;

        KeysHandler(long key) {
            this.key = key;
        }

        @Override
        public Object invoke(Object self, Method method, Object[] args) {
            switch (method.getName()) {
                case "next" -> {
                    boolean hasRow = !consumed;
                    consumed = true;
                    return hasRow;
                }
                case "getLong" -> {
                    return key;
                }
                case "toString" -> {
                    return "RecordingKeys[" + key + "]";
                }
                default -> {
                    return defaultValue(method.getReturnType());
                }
            }
        }
    }

    /** A harmless value of whatever type the unimplemented method returns. */
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == void.class) {
            return null;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        return 0;
    }
}
