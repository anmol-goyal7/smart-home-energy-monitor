package com.smarthome.energy.server;

import com.smarthome.energy.db.ConnectionFactory;
import com.smarthome.energy.db.DataAccessException;
import com.smarthome.energy.db.EventDao;
import com.smarthome.energy.db.ReadingDao;
import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.Reading;
import com.smarthome.energy.rules.RuleEngine;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The dispatcher sink that stores a reading, evaluates it, and stores whatever it triggered —
 * in one transaction — then hands the resulting alerts to the dashboard feed.
 *
 * <h2>Why persistence and detection are one sink and not two</h2>
 *
 * <p>They read as separate concerns, and the {@link ReadingDispatcher} would happily fan a
 * reading out to a "persistence" sink and a "detection" sink independently. They are one
 * because of a foreign key: {@code events.triggering_reading_id} points at the row the
 * reading insert generates, so detection cannot write an event until persistence has told it
 * the key — and the two writes have to be in the same transaction, or a crash between them
 * leaves an alert pointing at a reading that was rolled back. Two sinks would mean two
 * transactions and no way to relate them; one sink is what makes the pair atomic.</p>
 *
 * <p>The rule evaluation itself stays a pure function ({@link RuleEngine#evaluate}), so what
 * this class adds is only the transaction and the ordering:</p>
 *
 * <ol>
 *   <li>insert the reading, taking its generated key;</li>
 *   <li>evaluate the rules and bind their events to that key;</li>
 *   <li>insert the events;</li>
 *   <li>commit;</li>
 *   <li><em>then</em> publish the alerts to the dashboards.</li>
 * </ol>
 *
 * <p>Publishing last is deliberate. An operator who sees an alert on the dashboard should be
 * able to find it in the alert log afterwards; publishing before the commit would put alerts
 * on screen that a rolled-back transaction means never happened.</p>
 *
 * <p>This sink runs on a dispatcher worker, never on a {@code ClientHandler}'s read loop, so
 * the cost of the transaction and the evaluation cannot back-pressure meter ingestion. A
 * failure here throws {@link DataAccessException}; the dispatcher catches it, counts it
 * against this sink, and carries on with the next reading.</p>
 *
 * <p>Syllabus mapping: Unit III — JDBC transactions; Unit I — threading and composition.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class PersistenceSink implements ReadingDispatcher.Sink {

    private final ConnectionFactory connections;
    private final ReadingDao readings;
    private final EventDao events;
    private final RuleEngine engine;
    private final Consumer<Event> alertChannel;

    private final AtomicLong stored = new AtomicLong();
    private final AtomicLong alerted = new AtomicLong();

    /**
     * @param connections  where the transaction's connection comes from; must not be null
     * @param engine       the rule engine to evaluate each reading with; must not be null
     * @param alertChannel where committed alerts are published, normally
     *                     {@code DashboardPublisher::publishAlert}; must not be null
     * @throws NullPointerException if any argument is null
     */
    public PersistenceSink(ConnectionFactory connections, RuleEngine engine, Consumer<Event> alertChannel) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.alertChannel = Objects.requireNonNull(alertChannel, "alertChannel");
        this.readings = new ReadingDao(connections);
        this.events = new EventDao(connections);
    }

    @Override
    public String name() {
        return "persistence+detection";
    }

    @Override
    public void accept(Reading reading) {
        Objects.requireNonNull(reading, "reading");
        List<Event> raised = writeInOneTransaction(reading);

        // Outside the transaction: publishing is a socket offer that must not hold a database
        // connection open, and an alert that reached the dashboard cannot be un-sent anyway.
        for (Event event : raised) {
            alertChannel.accept(event);
        }
        stored.incrementAndGet();
        alerted.addAndGet(raised.size());
    }

    /** @return readings committed by this sink since start-up. */
    public long getStoredCount() {
        return stored.get();
    }

    /** @return alerts committed and published by this sink since start-up. */
    public long getAlertCount() {
        return alerted.get();
    }

    /** @return the engine this sink evaluates with, for the threshold editor's reload. */
    public RuleEngine getEngine() {
        return engine;
    }

    /**
     * Writes the reading and its events atomically, returning the events that were committed.
     */
    private List<Event> writeInOneTransaction(Reading reading) {
        try (Connection connection = connections.getConnection()) {
            boolean autoCommitBefore = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long readingId = readings.insert(connection, reading);
                List<Event> raised = engine.evaluate(reading, readingId);
                for (Event event : raised) {
                    events.insert(connection, event);
                }
                connection.commit();
                return raised;
            } catch (RuntimeException e) {
                // Almost always the DataAccessException a DAO raised; the reading and any
                // events it had already written go back with it.
                rollbackQuietly(connection, e);
                throw e;
            } catch (SQLException e) {
                rollbackQuietly(connection, e);
                throw new DataAccessException("failed to store reading " + reading, e);
            } finally {
                // The connection is about to be closed, but restoring the mode keeps this
                // correct if a pooled factory ever hands the same connection back out.
                restoreAutoCommit(connection, autoCommitBefore);
            }
        } catch (SQLException e) {
            throw new DataAccessException("failed to open a transaction for " + reading, e);
        }
    }

    /** Rolls back, attaching any rollback failure to the original one rather than replacing it. */
    private static void rollbackQuietly(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean previous) {
        try {
            connection.setAutoCommit(previous);
        } catch (SQLException e) {
            // The connection is closing; nothing here is recoverable and the caller already
            // has the failure that matters.
        }
    }
}
