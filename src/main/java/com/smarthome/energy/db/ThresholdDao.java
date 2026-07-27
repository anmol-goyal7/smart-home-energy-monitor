package com.smarthome.energy.db;

import com.smarthome.energy.model.Metric;
import com.smarthome.energy.model.Threshold;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Data access object for the {@code thresholds} table.
 *
 * <p>Loads the detection thresholds the rule engine evaluates against. Because thresholds
 * change rarely, the rule engine caches the result of {@link #findAll()} at start-up and
 * only reloads on demand, keeping the hot evaluation path free of database round-trips.</p>
 *
 * <p>API:
 * <ul>
 *   <li>{@link #findAll()}.</li>
 *   <li>{@link #findForDevice(int)} — device-specific plus defaults.</li>
 *   <li>{@link #upsert(Threshold)} — used by the dashboard's threshold editor.</li>
 * </ul>
 *
 * <p>{@link #findForDevice(int)} returns both the device's own rows and the global defaults,
 * ordered so that the device-specific row for a metric arrives after the default for the
 * same metric. That ordering is the whole contract: the caller builds its lookup by
 * inserting rows in the order received, so a specific override naturally lands on top of the
 * default it replaces without the caller having to compare precedence itself.</p>
 *
 * <p>Syllabus mapping: Unit III — Database connectivity via JDBC.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class ThresholdDao {

    private static final String SQL_FIND_ALL = """
            SELECT device_id, metric, min_value, max_value, description
              FROM thresholds
             ORDER BY metric, device_id IS NOT NULL, device_id
            """;

    /**
     * Defaults first, then the device's overrides. {@code device_id IS NOT NULL} sorts
     * false (0) before true (1), so within each metric the global row precedes the
     * device-specific one.
     */
    private static final String SQL_FIND_FOR_DEVICE = """
            SELECT device_id, metric, min_value, max_value, description
              FROM thresholds
             WHERE device_id IS NULL OR device_id = ?
             ORDER BY metric, device_id IS NOT NULL
            """;

    private static final String SQL_UPSERT = """
            INSERT INTO thresholds (device_id, metric, min_value, max_value, description)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                min_value   = VALUES(min_value),
                max_value   = VALUES(max_value),
                description = VALUES(description)
            """;

    private final ConnectionFactory connections;

    /**
     * @param connections factory this DAO takes its connections from; must not be null
     * @throws NullPointerException if {@code connections} is null
     */
    public ThresholdDao(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Returns every threshold row, defaults before overrides within each metric.
     *
     * @return all thresholds; empty if none have been seeded
     * @throws DataAccessException if the query fails
     */
    public List<Threshold> findAll() {
        try (Connection c = connections.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_FIND_ALL);
             ResultSet rs = ps.executeQuery()) {

            List<Threshold> thresholds = new ArrayList<>();
            while (rs.next()) {
                thresholds.add(mapRow(rs));
            }
            return thresholds;
        } catch (SQLException e) {
            throw new DataAccessException("failed to load thresholds", e);
        }
    }

    /**
     * Returns the thresholds that apply to one device: the global defaults plus that
     * device's own overrides, defaults first within each metric.
     *
     * @param deviceId the device to resolve thresholds for
     * @return the applicable thresholds
     * @throws DataAccessException if the query fails
     */
    public List<Threshold> findForDevice(int deviceId) {
        try (Connection c = connections.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_FIND_FOR_DEVICE)) {

            ps.setInt(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Threshold> thresholds = new ArrayList<>();
                while (rs.next()) {
                    thresholds.add(mapRow(rs));
                }
                return thresholds;
            }
        } catch (SQLException e) {
            throw new DataAccessException("failed to load thresholds for device " + deviceId, e);
        }
    }

    /**
     * Inserts a threshold, or updates the existing row for the same
     * {@code (device_id, metric)} pair.
     *
     * <p>The table's unique key on that pair is what makes this safe to express as a single
     * statement: the dashboard's editor does not need to know whether a device already has
     * an override for the metric being edited, and two editors committing at once cannot
     * produce a duplicate row.</p>
     *
     * @param threshold the threshold to store; must not be null
     * @throws DataAccessException  if the statement fails
     * @throws NullPointerException if {@code threshold} is null
     */
    public void upsert(Threshold threshold) {
        Objects.requireNonNull(threshold, "threshold");
        try (Connection c = connections.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_UPSERT)) {

            setNullableInt(ps, 1, threshold.getDeviceId());
            ps.setString(2, threshold.getMetric().name());
            setNullableDouble(ps, 3, threshold.getMinValue());
            setNullableDouble(ps, 4, threshold.getMaxValue());
            ps.setString(5, threshold.getDescription());
            ps.executeUpdate();

        } catch (SQLException e) {
            throw new DataAccessException("failed to store " + threshold, e);
        }
    }

    /** Maps the current row of a result set selecting the five threshold columns. */
    private static Threshold mapRow(ResultSet rs) throws SQLException {
        return new Threshold(
                rs.getObject("device_id", Integer.class),
                Metric.valueOf(rs.getString("metric")),
                rs.getObject("min_value", Double.class),
                rs.getObject("max_value", Double.class),
                rs.getString("description"));
    }

    /** Binds a nullable integer, using setNull rather than letting a null box badly. */
    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    /** Binds a nullable decimal bound; an absent bound is genuinely NULL, not zero. */
    private static void setNullableDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.DECIMAL);
        } else {
            ps.setDouble(index, value);
        }
    }
}
