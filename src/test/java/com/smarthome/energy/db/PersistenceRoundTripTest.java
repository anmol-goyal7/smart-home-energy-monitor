package com.smarthome.energy.db;

import com.smarthome.energy.model.Device;
import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.EventType;
import com.smarthome.energy.model.Metric;
import com.smarthome.energy.model.Reading;
import com.smarthome.energy.model.Severity;
import com.smarthome.energy.model.Threshold;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for the persistence layer, against a real MySQL instance.
 *
 * <p>These are the automated form of the Phase 1 milestone: write through a DAO, read back
 * through a DAO, and assert the value survived. They need the database from
 * {@code docker-compose.yml} to be up and seeded.</p>
 *
 * <p>When no database is reachable the whole class <em>skips</em> rather than fails, so
 * {@code mvn test} stays green on a machine without Docker and the model tests still run.
 * A skip is reported with the reason attached, so a genuinely broken connection is not
 * silently mistaken for an absent one.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
class PersistenceRoundTripTest {

    /** Seeded Kitchen Refrigerator; see sql/seed.sql. */
    private static final int SEEDED_DEVICE_ID = 1;

    /** An id the seed data deliberately does not contain. */
    private static final int ABSENT_DEVICE_ID = 9_999;

    private static ConnectionFactory connections;
    private static boolean databaseAvailable;
    private static String skipReason = "not probed";

    @BeforeAll
    static void probeDatabase() {
        try {
            ConnectionFactory candidate = ConnectionFactory.fromDefaultConfig();
            try (Connection c = candidate.getConnection()) {
                databaseAvailable = c.isValid(2);
                skipReason = databaseAvailable ? "" : "connection opened but did not validate";
            }
            connections = candidate;
        } catch (DataAccessException | SQLException e) {
            databaseAvailable = false;
            skipReason = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    @BeforeEach
    void requireDatabase() {
        Assumptions.assumeTrue(databaseAvailable,
                () -> "skipped, no database reachable (" + skipReason
                        + "). Run `docker compose up -d` to exercise these.");
    }

    @Test
    @DisplayName("a reading survives the round trip through the database unchanged")
    void readingRoundTripsByValue() {
        ReadingDao readings = new ReadingDao(connections);

        // reading_ts is DATETIME(3); sub-millisecond precision would be lost on the way in
        // and the value read back would not equal the value written.
        Instant when = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Reading written = new Reading(SEEDED_DEVICE_ID, when, 231.40, 1.62, 374.90);

        long readingId = readings.insert(written);
        List<Reading> readBack = readings.findByDeviceSince(SEEDED_DEVICE_ID, when.toEpochMilli());

        assertAll(
                () -> assertTrue(readingId > 0, "insert should return a generated key"),
                () -> assertTrue(readBack.contains(written),
                        "the reading written should be among those read back"));
    }

    @Test
    @DisplayName("findByDeviceSince excludes readings older than the bound")
    void findByDeviceSinceRespectsItsLowerBound() {
        ReadingDao readings = new ReadingDao(connections);

        Instant older = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(120);
        Reading old = new Reading(SEEDED_DEVICE_ID, older, 229.00, 1.50, 343.50);
        readings.insert(old);

        List<Reading> sinceOneMinuteAgo = readings.findByDeviceSince(
                SEEDED_DEVICE_ID, Instant.now().minusSeconds(60).toEpochMilli());

        assertFalse(sinceOneMinuteAgo.contains(old),
                "a reading two minutes old must not appear in a one-minute window");
    }

    @Test
    @DisplayName("readings come back ordered oldest first")
    void findByDeviceSinceOrdersByMeasurementTime() {
        ReadingDao readings = new ReadingDao(connections);

        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        readings.insert(new Reading(SEEDED_DEVICE_ID, base.plusMillis(200), 230.00, 1.50, 345.00));
        readings.insert(new Reading(SEEDED_DEVICE_ID, base.plusMillis(100), 230.00, 1.50, 345.00));

        List<Reading> window = readings.findByDeviceSince(SEEDED_DEVICE_ID, base.toEpochMilli());

        for (int i = 1; i < window.size(); i++) {
            Instant previous = window.get(i - 1).getReadingTimestamp();
            Instant current = window.get(i).getReadingTimestamp();
            assertFalse(current.isBefore(previous),
                    "row " + i + " (" + current + ") precedes row " + (i - 1) + " (" + previous + ")");
        }
    }

    @Test
    @DisplayName("the seeded device catalogue loads and is addressable by id")
    void deviceCatalogueLoads() {
        DeviceDao devices = new DeviceDao(connections);

        List<Device> all = devices.findAll();
        Optional<Device> refrigerator = devices.findById(SEEDED_DEVICE_ID);

        assertAll(
                () -> assertFalse(all.isEmpty(), "sql/seed.sql should have populated devices"),
                () -> assertTrue(refrigerator.isPresent(), "device 1 should be seeded"),
                () -> assertEquals(SEEDED_DEVICE_ID, refrigerator.orElseThrow().getDeviceId()));
    }

    @Test
    @DisplayName("looking up an unseeded device yields an empty Optional, not a failure")
    void missingDeviceYieldsEmpty() {
        DeviceDao devices = new DeviceDao(connections);

        assertTrue(devices.findById(ABSENT_DEVICE_ID).isEmpty());
    }

    @Test
    @DisplayName("a device resolves to the global voltage default plus its own power override")
    void thresholdsResolveDefaultsAndOverrides() {
        ThresholdDao thresholds = new ThresholdDao(connections);

        List<Threshold> applicable = thresholds.findForDevice(SEEDED_DEVICE_ID);

        boolean hasGlobalVoltageBand = applicable.stream()
                .anyMatch(t -> t.isGlobalDefault() && t.getMetric() == Metric.VOLTAGE);
        boolean hasDevicePowerCeiling = applicable.stream()
                .anyMatch(t -> !t.isGlobalDefault() && t.getMetric() == Metric.POWER && t.hasMax());

        assertAll(
                () -> assertTrue(hasGlobalVoltageBand, "the global VOLTAGE band should apply"),
                () -> assertTrue(hasDevicePowerCeiling, "device 1 has its own POWER ceiling"));
    }

    @Test
    @DisplayName("an event links to the reading id returned by the reading insert")
    void eventLinksToItsTriggeringReading() {
        ReadingDao readings = new ReadingDao(connections);
        EventDao events = new EventDao(connections);

        Instant when = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        long readingId = readings.insert(new Reading(SEEDED_DEVICE_ID, when, 231.40, 2.35, 540.00));

        events.insert(Event.raisedNow(SEEDED_DEVICE_ID, readingId, EventType.LOAD_OVERLOAD,
                Severity.WARNING, 540.00, 500.00, "round-trip test"));

        boolean linked = events.findByDevice(SEEDED_DEVICE_ID).stream()
                .anyMatch(e -> Long.valueOf(readingId).equals(e.getTriggeringReadingId()));

        assertTrue(linked, "the stored event should reference the reading that triggered it");
    }

    @Test
    @DisplayName("findRecent returns no more rows than asked for")
    void findRecentHonoursItsLimit() {
        EventDao events = new EventDao(connections);

        assertTrue(events.findRecent(2).size() <= 2);
    }
}
