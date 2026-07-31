package com.smarthome.energy.bench;

import com.smarthome.energy.db.ConnectionFactory;
import com.smarthome.energy.db.DataAccessException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A minimal fixed-size connection pool, for the third row of Evidence 2.
 *
 * <p>{@link ConnectionFactory} opens a fresh connection on every call, and every DAO method
 * that owns its own connection therefore pays a TCP handshake and a MySQL authentication
 * round trip. That is the honest starting point the project shipped with, and the cost of it
 * is the difference between the second and third rows of the JDBC benchmark. This class
 * exists to put a number on that difference.</p>
 *
 * <h2>Why it lives in the benchmark package</h2>
 *
 * <p>A pool in {@code db} would be a pool the rest of the system could reach for, and this one
 * is not fit for that: it has no validation of borrowed connections, no eviction of stale
 * ones, no timeout policy worth the name, and no story for a connection that dies while
 * checked out. It measures a cost; it is not infrastructure. Keeping it here means the
 * benchmark can make its point without anything in the running system quietly depending on
 * eighty lines of unhardened pooling.</p>
 *
 * <p>Borrowers call {@link #borrow()} and {@link #release(Connection)} explicitly rather than
 * getting a proxied {@code Connection} whose {@code close()} returns it to the pool. The proxy
 * is the friendlier API and the wrong one here: this code is evidence, and evidence should be
 * readable without knowing that {@code close()} does not mean close.</p>
 *
 * <p>Syllabus mapping: Unit III — JDBC connection management.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
final class ConnectionPool implements AutoCloseable {

    /** How long {@link #borrow()} waits for a connection before giving up. */
    private static final long BORROW_TIMEOUT_SECONDS = 10L;

    private final int size;
    private final BlockingQueue<Connection> idle;
    private final List<Connection> all;

    /**
     * Opens {@code size} connections up front, so the pool's cost is paid before the timed
     * section rather than inside it.
     *
     * @param connections the factory to fill the pool from; must not be null
     * @param size        how many connections to hold; must be positive
     * @throws IllegalArgumentException if {@code size} is not positive
     * @throws DataAccessException      if a connection cannot be opened
     * @throws NullPointerException     if {@code connections} is null
     */
    ConnectionPool(ConnectionFactory connections, int size) {
        Objects.requireNonNull(connections, "connections");
        if (size <= 0) {
            throw new IllegalArgumentException("pool size must be positive, was " + size);
        }
        this.size = size;
        this.idle = new ArrayBlockingQueue<>(size);
        this.all = new ArrayList<>(size);
        try {
            for (int i = 0; i < size; i++) {
                Connection connection = connections.getConnection();
                all.add(connection);
                idle.add(connection);
            }
        } catch (SQLException e) {
            closeAll();
            throw new DataAccessException("could not fill a pool of " + size + " connection(s)", e);
        }
    }

    /** @return how many connections this pool holds. */
    int size() {
        return size;
    }

    /**
     * Takes a connection out of the pool, waiting if they are all checked out.
     *
     * @return a connection the caller must hand back to {@link #release(Connection)}
     * @throws DataAccessException if no connection became available in time, or the wait was
     *                             interrupted
     */
    Connection borrow() {
        try {
            Connection connection = idle.poll(BORROW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (connection == null) {
                throw new DataAccessException("timed out waiting for a pooled connection; all "
                        + size + " are checked out");
            }
            return connection;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataAccessException("interrupted while waiting for a pooled connection");
        }
    }

    /**
     * Returns a borrowed connection.
     *
     * @param connection the connection from {@link #borrow()}; ignored if null
     */
    void release(Connection connection) {
        if (connection != null) {
            idle.offer(connection);
        }
    }

    /** Closes every connection the pool opened, borrowed or not. */
    @Override
    public void close() {
        closeAll();
    }

    private void closeAll() {
        for (Connection connection : all) {
            try {
                connection.close();
            } catch (SQLException e) {
                // Tearing down after a measurement; nothing here is recoverable.
            }
        }
        all.clear();
        idle.clear();
    }

    @Override
    public String toString() {
        return "ConnectionPool[size=" + size + ", idle=" + idle.size() + "]";
    }
}
