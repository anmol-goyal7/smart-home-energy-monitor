package com.smarthome.energy.db;

import com.smarthome.energy.model.Reading;

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
import java.util.Objects;

/**
 * Data access object for the {@code readings} table.
 *
 * <p>Owns all SQL for persisting and querying {@code Reading} rows. Inserts are performed
 * with a {@code PreparedStatement} on the ingest path; range queries (by device and time
 * window) back the dashboard's history charts and feed the Python analytics module's
 * source data.</p>
 *
 * <p>API:
 * <ul>
 *   <li>{@link #insert(Reading)} — parameterised insert of one reading, returning its key.</li>
 *   <li>{@link #findByDeviceSince(int, long)} — history for one device from an instant.</li>
 * </ul>
 *
 * <h2>Two notes on the choices here</h2>
 *
 * <p><strong>Insert returns the generated key.</strong> The scaffold sketched this as
 * {@code void}, but {@code events.triggering_reading_id} is a foreign key to
 * {@code readings.reading_id}, so the rule engine has to know the id of the row it just
 * wrote in order to link an alert to it. Asking for the key on the way out costs nothing —
 * {@code RETURN_GENERATED_KEYS} rides along with the insert — whereas recovering it
 * afterwards means a second round trip and a query that cannot reliably pick its own row
 * out of a table several meters are writing to concurrently.</p>
 *
 * <p><strong>Timestamps cross the boundary as {@link LocalDateTime} at UTC.</strong>
 * {@code reading_ts} is a {@code DATETIME(3)}, which stores no zone. Binding a
 * {@code java.sql.Timestamp} would let the driver interpret it in whatever zone the JVM
 * happens to run in, so the same reading would land differently on a developer's laptop and
 * in the container. Converting explicitly through {@link ZoneOffset#UTC} on both the write
 * and the read makes the stored value mean exactly one thing.</p>
 *
 * <p>Syllabus mapping: Unit III — Database connectivity via JDBC (PreparedStatement, CRUD).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class ReadingDao {

    private static final String SQL_INSERT = """
            INSERT INTO readings (device_id, reading_ts, voltage, current_amp, power_watts)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String SQL_FIND_BY_DEVICE_SINCE = """
            SELECT device_id, reading_ts, voltage, current_amp, power_watts
              FROM readings
             WHERE device_id = ?
               AND reading_ts >= ?
             ORDER BY reading_ts
            """;

    private static final String SQL_COUNT_BY_DEVICE = """
            SELECT COUNT(*) FROM readings WHERE device_id = ?
            """;

    private final ConnectionFactory connections;

    /**
     * @param connections factory this DAO takes its connections from; must not be null
     * @throws NullPointerException if {@code connections} is null
     */
    public ReadingDao(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Persists one reading.
     *
     * @param reading the reading to store; must not be null
     * @return the generated {@code reading_id}
     * @throws DataAccessException  if the insert fails
     * @throws NullPointerException if {@code reading} is null
     */
    public long insert(Reading reading) {
        Objects.requireNonNull(reading, "reading");
        try (Connection c = connections.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_INSERT, Statement.RETURN_GENERATED_KEYS)) {

            ps.setInt(1, reading.getDeviceId());
            ps.setObject(2, utc(reading.getReadingTimestamp()));
            ps.setDouble(3, reading.getVoltage());
            ps.setDouble(4, reading.getCurrent());
            ps.setDouble(5, reading.getPowerWatts());
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new DataAccessException("insert of " + reading + " returned no generated key");
            }
        } catch (SQLException e) {
            throw new DataAccessException("failed to insert reading for device " + reading.getDeviceId(), e);
        }
    }

    /**
     * Returns one device's readings from an instant onwards, oldest first.
     *
     * @param deviceId    the device to read history for
     * @param sinceMillis lower bound on {@code reading_ts}, as epoch milliseconds, inclusive
     * @return the matching readings ordered by measurement time; empty if there are none
     * @throws DataAccessException if the query fails
     */
    public List<Reading> findByDeviceSince(int deviceId, long sinceMillis) {
        return findByDeviceSince(deviceId, Instant.ofEpochMilli(sinceMillis));
    }

    /**
     * Returns one device's readings from an instant onwards, oldest first.
     *
     * @param deviceId the device to read history for
     * @param since    lower bound on {@code reading_ts}, inclusive; must not be null
     * @return the matching readings ordered by measurement time; empty if there are none
     * @throws DataAccessException  if the query fails
     * @throws NullPointerException if {@code since} is null
     */
    public List<Reading> findByDeviceSince(int deviceId, Instant since) {
        Objects.requireNonNull(since, "since");
        try (Connection c = connections.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_DEVICE_SINCE)) {

            ps.setInt(1, deviceId);
            ps.setObject(2, utc(since));

            try (ResultSet rs = ps.executeQuery()) {
                List<Reading> readings = new ArrayList<>();
                while (rs.next()) {
                    readings.add(mapRow(rs));
                }
                return readings;
            }
        } catch (SQLException e) {
            throw new DataAccessException("failed to load readings for device " + deviceId
                    + " since " + since, e);
        }
    }

    /**
     * Counts the rows stored for one device. Used by the Phase 1 verification and by the
     * JDBC benchmark, which needs a row count it did not derive from its own bookkeeping.
     *
     * @param deviceId the device to count readings for
     * @return the number of stored readings
     * @throws DataAccessException if the query fails
     */
    public long countByDevice(int deviceId) {
        try (Connection c = connections.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_COUNT_BY_DEVICE)) {

            ps.setInt(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new DataAccessException("failed to count readings for device " + deviceId, e);
        }
    }

    /** Maps the current row of a result set selecting the five reading columns. */
    private static Reading mapRow(ResultSet rs) throws SQLException {
        LocalDateTime ts = rs.getObject("reading_ts", LocalDateTime.class);
        return new Reading(
                rs.getInt("device_id"),
                ts.toInstant(ZoneOffset.UTC),
                rs.getDouble("voltage"),
                rs.getDouble("current_amp"),
                rs.getDouble("power_watts"));
    }

    /** Converts an instant to the zone-free local time actually stored in {@code DATETIME(3)}. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
