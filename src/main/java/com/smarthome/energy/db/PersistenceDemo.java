package com.smarthome.energy.db;

import com.smarthome.energy.model.Device;
import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.EventType;
import com.smarthome.energy.model.Reading;
import com.smarthome.energy.model.Severity;
import com.smarthome.energy.model.Threshold;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Exercises the whole persistence layer against a live database and prints what it did.
 *
 * <p>This is the Phase 1 milestone made runnable: it proves the schema, the connection
 * factory, and all four DAOs work together before any socket or GUI code exists to depend on
 * them. Run it after {@code docker compose up -d}:</p>
 *
 * <pre>
 *   mvn exec:java -Dexec.mainClass=com.smarthome.energy.db.PersistenceDemo
 * </pre>
 *
 * <p>It performs a full round trip: read the seeded catalogue, insert a reading and read it
 * back, resolve the thresholds that apply to a device, then raise an event against the
 * inserted reading and read that back too. The event step matters more than it looks —
 * it is the only check that the foreign key from {@code events.triggering_reading_id} to the
 * key returned by {@link ReadingDao#insert} actually lines up.</p>
 *
 * <p>Nothing here is cleaned up afterwards: the rows it writes are ordinary data, and
 * leaving them makes the effect visible in a SQL client. Re-running simply adds more.</p>
 *
 * <p>Syllabus mapping: Unit III — Database connectivity via JDBC.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class PersistenceDemo {

    /** Device used for the write steps; id 1 is the seeded Kitchen Refrigerator. */
    private static final int DEMO_DEVICE_ID = 1;

    private PersistenceDemo() {
        // Entry point only.
    }

    /**
     * Runs the round trip.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        ConnectionFactory connections;
        try {
            connections = ConnectionFactory.fromDefaultConfig();
        } catch (DataAccessException e) {
            System.err.println("Configuration problem: " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("Connecting to " + connections.getUrl());
        System.out.println();

        DeviceDao devices = new DeviceDao(connections);
        ReadingDao readings = new ReadingDao(connections);
        ThresholdDao thresholds = new ThresholdDao(connections);
        EventDao events = new EventDao(connections);

        try {
            List<Device> catalogue = showCatalogue(devices);
            if (catalogue.isEmpty()) {
                System.err.println("The device catalogue is empty — load sql/seed.sql before running this.");
                System.exit(1);
                return;
            }

            long readingId = insertAndReadBack(readings);
            showThresholds(thresholds);
            raiseAndReadBackEvent(events, readingId);

            System.out.println();
            System.out.println("Phase 1 round trip complete.");

        } catch (DataAccessException e) {
            System.err.println();
            System.err.println("Persistence failure: " + e.getMessage());
            if (e.getSqlCause() != null) {
                System.err.println("  SQLState " + e.getSqlCause().getSQLState()
                        + ", vendor code " + e.getSqlCause().getErrorCode());
            }
            System.exit(1);
        }
    }

    /** Reads and prints the seeded appliance catalogue. */
    private static List<Device> showCatalogue(DeviceDao devices) {
        System.out.println("== devices ==");
        List<Device> catalogue = devices.findAll();
        for (Device device : catalogue) {
            System.out.printf("  %2d  %-22s %-12s %-12s %8.2f W%n",
                    device.getDeviceId(), device.getName(), device.getApplianceType(),
                    device.getLocation(), device.getRatedPowerWatts());
        }
        System.out.println("  (" + catalogue.size() + " devices)");
        System.out.println();
        return catalogue;
    }

    /**
     * Inserts one reading and reads it back out of the database.
     *
     * @return the generated {@code reading_id}
     */
    private static long insertAndReadBack(ReadingDao readings) {
        System.out.println("== insert a reading, then read it back ==");

        // Truncate to milliseconds: reading_ts is DATETIME(3), so anything finer is lost on
        // the way in and the value read back would not equal the value written.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Reading written = new Reading(DEMO_DEVICE_ID, now, 231.40, 1.62, 374.90);

        long readingId = readings.insert(written);
        System.out.println("  wrote   " + written);
        System.out.println("  key     reading_id = " + readingId);

        List<Reading> readBack = readings.findByDeviceSince(DEMO_DEVICE_ID, now.toEpochMilli());
        System.out.println("  read    " + readBack.size() + " reading(s) at or after that instant");
        for (Reading r : readBack) {
            System.out.println("          " + r);
        }

        boolean roundTripped = readBack.contains(written);
        System.out.println("  match   " + (roundTripped
                ? "yes — the row read back equals the object written"
                : "NO — the value written did not survive the round trip"));
        System.out.println("  total   " + readings.countByDevice(DEMO_DEVICE_ID)
                + " reading(s) stored for device " + DEMO_DEVICE_ID);
        System.out.println();
        return readingId;
    }

    /** Resolves and prints the thresholds that apply to the demo device. */
    private static void showThresholds(ThresholdDao thresholds) {
        System.out.println("== thresholds applying to device " + DEMO_DEVICE_ID + " ==");
        System.out.println("  (global defaults first, then this device's overrides)");
        for (Threshold t : thresholds.findForDevice(DEMO_DEVICE_ID)) {
            System.out.printf("  %-8s %-7s min=%-9s max=%-9s %s%n",
                    t.isGlobalDefault() ? "default" : "device",
                    t.getMetric(),
                    t.getMinValue() == null ? "-" : t.getMinValue(),
                    t.getMaxValue() == null ? "-" : t.getMaxValue(),
                    t.getDescription() == null ? "" : t.getDescription());
        }
        System.out.println();
    }

    /** Raises an event against the reading just inserted and reads it back. */
    private static void raiseAndReadBackEvent(EventDao events, long readingId) {
        System.out.println("== raise an event against that reading, then read it back ==");

        Event raised = Event.raisedNow(DEMO_DEVICE_ID, readingId, EventType.LOAD_OVERLOAD,
                Severity.WARNING, 540.00, 500.00,
                "PersistenceDemo: synthetic overload against reading " + readingId);
        events.insert(raised);
        System.out.println("  wrote   " + raised);

        List<Event> recent = events.findRecent(3);
        System.out.println("  read    " + recent.size() + " most recent event(s):");
        for (Event e : recent) {
            System.out.println("          " + e + " <- reading " + e.getTriggeringReadingId());
        }
    }
}
