package com.smarthome.energy.server;

import com.smarthome.energy.db.DataAccessException;
import com.smarthome.energy.db.RecordingDatabase;
import com.smarthome.energy.model.Device;
import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.Metric;
import com.smarthome.energy.model.Reading;
import com.smarthome.energy.model.Threshold;
import com.smarthome.energy.rules.RuleContext;
import com.smarthome.energy.rules.RuleEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the sink that ties persistence to detection: that a reading and the events it
 * triggered are written on one connection inside one transaction, that a failure anywhere in
 * that sequence rolls the whole thing back, and that alerts reach the dashboard only after
 * the commit.
 *
 * <p>These are assertions about the order of JDBC calls, so they run against
 * {@link RecordingDatabase} rather than MySQL and therefore run everywhere. What they do not
 * cover is whether MySQL accepts the SQL — that is what the DAO round-trip tests are for, and
 * those skip themselves when no database is reachable.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
class PersistenceSinkTest {

    private static final int FRIDGE = 1;

    private final List<Event> published = new ArrayList<>();

    @BeforeEach
    void resetDatabase() {
        RecordingDatabase.reset();
        published.clear();
    }

    private PersistenceSink sink() {
        RuleContext context = new RuleContext(
                List.of(new Device(FRIDGE, "Kitchen Refrigerator", "REFRIGERATOR", "Kitchen", 230.0, 350.0)),
                List.of(
                        new Threshold(null, Metric.VOLTAGE, 207.0, 253.0, "supply band"),
                        new Threshold(FRIDGE, Metric.POWER, null, 500.0, "overload ceiling")));
        return new PersistenceSink(RecordingDatabase.connectionFactory(),
                new RuleEngine(context), published::add);
    }

    private static Reading reading(double volts, double watts) {
        return new Reading(FRIDGE, Instant.ofEpochMilli(1_721_817_600_000L), volts, watts / volts, watts);
    }

    @Test
    @DisplayName("a nominal reading is one insert in one transaction, and no alert")
    void nominalReadingWritesOnlyTheReading() {
        sink().accept(reading(230.0, 180.0));

        assertEquals(List.of("open", "autoCommit=false", "insert readings", "commit",
                        "autoCommit=true", "close"),
                RecordingDatabase.calls());
        assertTrue(published.isEmpty());
    }

    @Test
    @DisplayName("an alerting reading writes the reading and its event in the same transaction")
    void alertingReadingWritesBothBeforeCommitting() {
        sink().accept(reading(264.0, 180.0));

        assertEquals(List.of("open", "autoCommit=false", "insert readings", "insert events",
                        "commit", "autoCommit=true", "close"),
                RecordingDatabase.calls());
    }

    @Test
    @DisplayName("both events of a doubly-bad reading are written before the single commit")
    void twoEventsStillOneTransaction() {
        sink().accept(reading(190.0, 620.0));

        assertEquals(List.of("open", "autoCommit=false", "insert readings", "insert events",
                        "insert events", "commit", "autoCommit=true", "close"),
                RecordingDatabase.calls());
        assertEquals(2, published.size());
    }

    @Test
    @DisplayName("the event is bound to the key the reading insert generated")
    void eventCarriesTheGeneratedReadingId() {
        sink().accept(reading(264.0, 180.0));

        assertEquals(1, published.size());
        assertEquals(1_001L, published.get(0).getTriggeringReadingId(),
                "the recording driver hands out 1001 for the first insert");
    }

    @Test
    @DisplayName("an event insert that fails rolls the reading back with it")
    void aFailedEventInsertRollsBackTheReading() {
        RecordingDatabase.failOn("INSERT INTO events");
        PersistenceSink sink = sink();

        assertThrows(DataAccessException.class, () -> sink.accept(reading(264.0, 180.0)));

        assertEquals(List.of("open", "autoCommit=false", "insert readings", "failed insert events",
                        "rollback", "autoCommit=true", "close"),
                RecordingDatabase.calls());
        assertTrue(RecordingDatabase.calls().stream().noneMatch("commit"::equals),
                "nothing may be committed when the pair could not be completed");
    }

    @Test
    @DisplayName("nothing is published when nothing was committed")
    void aRolledBackAlertIsNeverShownToTheOperator() {
        RecordingDatabase.failOn("INSERT INTO events");
        PersistenceSink sink = sink();

        assertThrows(DataAccessException.class, () -> sink.accept(reading(264.0, 180.0)));

        assertTrue(published.isEmpty(),
                "an alert on the dashboard that is not in the alert log is one the operator "
                        + "cannot go back and find");
    }

    @Test
    @DisplayName("a failed reading insert is rolled back too, and evaluates nothing")
    void aFailedReadingInsertStopsTheSequence() {
        RecordingDatabase.failOn("INSERT INTO readings");
        PersistenceSink sink = sink();

        assertThrows(DataAccessException.class, () -> sink.accept(reading(264.0, 180.0)));

        assertEquals(List.of("open", "autoCommit=false", "failed insert readings", "rollback",
                        "autoCommit=true", "close"),
                RecordingDatabase.calls());
        assertEquals(0, sink.getEngine().getEvaluatedCount(),
                "there is no reading to evaluate if it could not be stored");
    }

    @Test
    @DisplayName("the connection is closed whether the transaction committed or rolled back")
    void connectionsAreNeverLeaked() {
        PersistenceSink sink = sink();
        sink.accept(reading(230.0, 180.0));
        assertEquals(0, RecordingDatabase.openConnections());

        RecordingDatabase.failOn("INSERT INTO events");
        assertThrows(DataAccessException.class, () -> sink.accept(reading(264.0, 180.0)));
        assertEquals(0, RecordingDatabase.openConnections());
    }

    @Test
    @DisplayName("the sink counts what it stored and what it alerted on")
    void sinkReportsItsOwnTotals() {
        PersistenceSink sink = sink();
        sink.accept(reading(230.0, 180.0));
        sink.accept(reading(264.0, 180.0));

        assertEquals(2, sink.getStoredCount());
        assertEquals(1, sink.getAlertCount());
        assertEquals("persistence+detection", sink.name());
    }
}
