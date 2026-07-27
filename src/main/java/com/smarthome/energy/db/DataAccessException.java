package com.smarthome.energy.db;

import java.sql.SQLException;

/**
 * Unchecked wrapper around a {@link SQLException} raised inside the persistence layer.
 *
 * <p>Every DAO catches {@code SQLException} and rethrows it as this type. The reason is the
 * shape of the callers rather than a dislike of checked exceptions: readings are persisted
 * from the {@code ReadingDispatcher}'s worker and from {@code ClientHandler} threads, neither
 * of which can do anything locally about a database that is down — they cannot retry
 * meaningfully, and they must not stop reading from their socket. Declaring
 * {@code throws SQLException} all the way up that path would add plumbing to every signature
 * in exchange for a decision no intermediate frame is able to make.</p>
 *
 * <p>The original {@code SQLException} is always kept as the cause, so its SQLState and
 * vendor error code survive for logging and for the failure demonstrations in the report.</p>
 *
 * <p>Syllabus mapping: Unit I — exception design; Unit III — JDBC error handling.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public class DataAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what the persistence layer was attempting
     * @param cause   the underlying JDBC failure
     */
    public DataAccessException(String message, SQLException cause) {
        super(message, cause);
    }

    /**
     * @param message what went wrong; used for configuration faults that have no
     *                {@code SQLException} behind them
     */
    public DataAccessException(String message) {
        super(message);
    }

    /**
     * @return the underlying {@link SQLException}, or {@code null} if this exception was
     *         raised for a configuration fault rather than a JDBC failure
     */
    public SQLException getSqlCause() {
        return getCause() instanceof SQLException sql ? sql : null;
    }
}
