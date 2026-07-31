package com.smarthome.energy.demo;

import com.smarthome.energy.db.ConnectionFactory;
import com.smarthome.energy.db.DataAccessException;
import com.smarthome.energy.db.EventDao;
import com.smarthome.energy.db.ReadingDao;
import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.EventType;
import com.smarthome.energy.model.Reading;
import com.smarthome.energy.model.Severity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Unit III: a reading committed, an event that then fails, and the orphan left behind.
 *
 * <p>{@code PersistenceSink} writes a reading and the events it triggered inside one
 * transaction. The reason is a foreign key — {@code events.triggering_reading_id} points at
 * the row the reading insert generates — but the consequence is the thing worth showing: what
 * the database looks like when the second insert fails and the first one has already been
 * committed.</p>
 *
 * <p>Both halves below write a reading and then attempt an event that cannot be stored. The
 * failure is real, not simulated: the event names a {@code triggering_reading_id} that does
 * not exist, so InnoDB refuses it on {@code fk_events_reading}. The difference between the
 * two runs is only whether autocommit was on.</p>
 *
 * <p>The orphan the broken version leaves is not a crash and not an error in any log the
 * next morning. It is one extra reading, indistinguishable from a real one, that the alert it
 * was supposed to carry never got attached to — so the operator sees a normal-looking history
 * with a fault missing from it. Both rows written here are stamped in a sentinel window and
 * deleted before the demonstration returns, orphan included.</p>
 *
 * <p>Syllabus mapping: Unit III — JDBC transactions (atomicity, rollback).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
final class PartialWriteDemo {

    /** Device the demonstration rows are attributed to; must exist in {@code devices}. */
    private static final int DEVICE_ID = 1;

    /** A reading id that certainly does not exist, so the event insert fails on its foreign key. */
    private static final long MISSING_READING_ID = 9_000_000_000L;

    /** Both demonstration readings are stamped here, so the cleanup can find exactly them. */
    private static final Instant SENTINEL = Instant.parse("1999-01-01T00:00:00Z");

    private static final Instant SENTINEL_END = Instant.parse("1999-01-02T00:00:00Z");

    private PartialWriteDemo() {
        // Static entry point only.
    }

    /**
     * Runs the write both ways and reports how many rows each left behind.
     *
     * @param connections where the connections come from; must not be null
     * @return true if the demonstration behaved as expected: the broken version left an
     *         orphan and the corrected one left nothing
     */
    static boolean run(ConnectionFactory connections) {
        cleanup(connections);

        System.out.println("Writing a reading, then an event that cannot be stored.");
        System.out.println("The event names reading id " + MISSING_READING_ID + ", which does not");
        System.out.println("exist, so InnoDB refuses it on fk_events_reading. Only the transaction");
        System.out.println("differs between the two runs.");
        System.out.println();

        String brokenFailure = runBroken(connections);
        long afterBroken = countSentinelReadings(connections);
        System.out.println("  BROKEN     autocommit, two statements");
        System.out.println("               event insert failed: " + brokenFailure);
        System.out.println("               readings left behind: " + afterBroken
                + (afterBroken > 0 ? "  <- an orphan: a reading whose alert was never recorded" : ""));
        System.out.println();

        cleanup(connections);

        String correctedFailure = runCorrected(connections);
        long afterCorrected = countSentinelReadings(connections);
        System.out.println("  CORRECTED  one transaction, rolled back on failure");
        System.out.println("               event insert failed: " + correctedFailure);
        System.out.println("               readings left behind: " + afterCorrected
                + (afterCorrected == 0 ? "  <- the reading went back with the event" : ""));
        System.out.println();

        cleanup(connections);

        System.out.println("Both runs failed. Only one of them left the database telling a lie.");
        return afterBroken > 0 && afterCorrected == 0;
    }

    /**
     * The write as it must not be done: two statements, autocommit, no relationship between
     * them.
     *
     * @return the message the failing event insert produced
     */
    private static String runBroken(ConnectionFactory connections) {
        try (Connection connection = connections.getConnection()) {
            // Autocommit is JDBC's default, so this is what happens when nobody thinks about
            // transactions at all — the reading is durable the instant it is inserted.
            connection.setAutoCommit(true);
            new ReadingDao(connections).insert(connection, sentinelReading());
            try {
                new EventDao(connections).insert(connection, doomedEvent());
                return "it did not fail — the demonstration is broken, not the code";
            } catch (DataAccessException e) {
                return rootMessage(e);
            }
        } catch (SQLException e) {
            throw new DataAccessException("the broken partial-write demonstration could not run", e);
        }
    }

    /**
     * The write as {@code PersistenceSink} does it.
     *
     * @return the message the failing event insert produced
     */
    private static String runCorrected(ConnectionFactory connections) {
        try (Connection connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            try {
                new ReadingDao(connections).insert(connection, sentinelReading());
                new EventDao(connections).insert(connection, doomedEvent());
                connection.commit();
                return "it did not fail — the demonstration is broken, not the code";
            } catch (DataAccessException e) {
                connection.rollback();
                return rootMessage(e);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DataAccessException("the corrected partial-write demonstration could not run", e);
        }
    }

    private static Reading sentinelReading() {
        return new Reading(DEVICE_ID, SENTINEL, 230.00, 4.35, 1000.50);
    }

    /** An event that cannot be stored, because the reading it points at does not exist. */
    private static Event doomedEvent() {
        return new Event(DEVICE_ID, MISSING_READING_ID, EventType.VOLTAGE_SPIKE, Severity.CRITICAL,
                264.00, 253.00, "partial-write demonstration", SENTINEL);
    }

    /** @return how many rows the demonstration has left in its sentinel window. */
    private static long countSentinelReadings(ConnectionFactory connections) {
        try (Connection connection = connections.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT COUNT(*) FROM readings WHERE reading_ts >= ? AND reading_ts < ?")) {
            ps.setObject(1, LocalDateTime.ofInstant(SENTINEL, ZoneOffset.UTC));
            ps.setObject(2, LocalDateTime.ofInstant(SENTINEL_END, ZoneOffset.UTC));
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new DataAccessException("could not count the demonstration's rows", e);
        }
    }

    /** Deletes everything in the sentinel window, so no run leaks rows into the real history. */
    private static void cleanup(ConnectionFactory connections) {
        try (Connection connection = connections.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "DELETE FROM readings WHERE reading_ts >= ? AND reading_ts < ?")) {
            ps.setObject(1, LocalDateTime.ofInstant(SENTINEL, ZoneOffset.UTC));
            ps.setObject(2, LocalDateTime.ofInstant(SENTINEL_END, ZoneOffset.UTC));
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[demo] could not clean up the demonstration's rows: " + e.getMessage());
        }
    }

    /** The database's complaint, without the DAO's wrapper around it. */
    private static String rootMessage(Exception failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        String message = cause.getMessage();
        return message == null ? cause.toString() : message.split("\n")[0];
    }
}
