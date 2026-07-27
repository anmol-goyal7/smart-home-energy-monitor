package com.smarthome.energy.db;

import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.EventType;
import com.smarthome.energy.model.Severity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Data access object for the {@code events} table.
 *
 * <p>Persists the power-quality {@code Event}s raised by the rule engine and queries them
 * back for the dashboard's alert log. Each insert links the event to its device and, when
 * known, to the triggering reading.</p>
 *
 * <p>API:
 * <ul>
 *   <li>{@link #insert(Event)}.</li>
 *   <li>{@link #findRecent(int)} — newest alerts first.</li>
 *   <li>{@link #findByDevice(int)}.</li>
 * </ul>
 *
 * <p>Both queries order by {@code detected_at} descending, which is what the alert log
 * wants and what the {@code idx_events_recent} and {@code idx_events_device_time} indexes
 * are there to serve — an index is readable backwards, so the descending order costs no
 * sort.</p>
 *
 * <p>Syllabus mapping: Unit III — Database connectivity via JDBC.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class EventDao {

    private static final String SQL_INSERT = """
            INSERT INTO events (device_id, triggering_reading_id, event_type, severity,
                                measured_value, threshold_value, detail, detected_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SQL_FIND_RECENT = """
            SELECT device_id, triggering_reading_id, event_type, severity,
                   measured_value, threshold_value, detail, detected_at
              FROM events
             ORDER BY detected_at DESC
             LIMIT ?
            """;

    private static final String SQL_FIND_BY_DEVICE = """
            SELECT device_id, triggering_reading_id, event_type, severity,
                   measured_value, threshold_value, detail, detected_at
              FROM events
             WHERE device_id = ?
             ORDER BY detected_at DESC
            """;

    private final ConnectionFactory connections;

    /**
     * @param connections factory this DAO takes its connections from; must not be null
     * @throws NullPointerException if {@code connections} is null
     */
    public EventDao(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Persists one alert.
     *
     * @param event the event to store; must not be null
     * @throws DataAccessException  if the insert fails
     * @throws NullPointerException if {@code event} is null
     */
    public void insert(Event event) {
        Objects.requireNonNull(event, "event");
        try (Connection c = connections.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_INSERT)) {

            ps.setInt(1, event.getDeviceId());
            Long readingId = event.getTriggeringReadingId();
            if (readingId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, readingId);
            }
            ps.setString(3, event.getType().name());
            ps.setString(4, event.getSeverity().name());
            ps.setDouble(5, event.getMeasuredValue());
            ps.setDouble(6, event.getThresholdValue());
            ps.setString(7, event.getDetail());
            ps.setObject(8, LocalDateTime.ofInstant(event.getDetectedAt(), ZoneOffset.UTC));
            ps.executeUpdate();

        } catch (SQLException e) {
            throw new DataAccessException("failed to insert " + event, e);
        }
    }

    /**
     * Returns the most recent alerts across all devices, newest first.
     *
     * @param limit maximum number of rows to return; must be positive
     * @return the newest events, at most {@code limit} of them
     * @throws DataAccessException      if the query fails
     * @throws IllegalArgumentException if {@code limit} is not positive
     */
    public List<Event> findRecent(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, was " + limit);
        }
        try (Connection c = connections.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_FIND_RECENT)) {

            ps.setInt(1, limit);
            return readAll(ps);

        } catch (SQLException e) {
            throw new DataAccessException("failed to load the " + limit + " most recent events", e);
        }
    }

    /**
     * Returns every alert raised for one device, newest first.
     *
     * @param deviceId the device to read the alert history of
     * @return that device's events; empty if it has never alerted
     * @throws DataAccessException if the query fails
     */
    public List<Event> findByDevice(int deviceId) {
        try (Connection c = connections.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_DEVICE)) {

            ps.setInt(1, deviceId);
            return readAll(ps);

        } catch (SQLException e) {
            throw new DataAccessException("failed to load events for device " + deviceId, e);
        }
    }

    /** Executes a prepared select and maps every row. */
    private static List<Event> readAll(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<Event> events = new ArrayList<>();
            while (rs.next()) {
                events.add(mapRow(rs));
            }
            return events;
        }
    }

    /** Maps the current row of a result set selecting the eight event columns. */
    private static Event mapRow(ResultSet rs) throws SQLException {
        LocalDateTime detectedAt = rs.getObject("detected_at", LocalDateTime.class);
        return new Event(
                rs.getInt("device_id"),
                rs.getObject("triggering_reading_id", Long.class),
                EventType.valueOf(rs.getString("event_type")),
                Severity.valueOf(rs.getString("severity")),
                rs.getDouble("measured_value"),
                rs.getDouble("threshold_value"),
                rs.getString("detail"),
                detectedAt.toInstant(ZoneOffset.UTC));
    }
}
