package com.smarthome.energy.db;

import com.smarthome.energy.model.Device;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Data access object for the {@code devices} table.
 *
 * <p>Provides read access to the relatively static device catalogue and the ability to
 * register a new device. The server loads the device list at start-up so it can resolve a
 * meter's {@code deviceId} to its rated values, and the dashboard uses it to label panels.</p>
 *
 * <p>API:
 * <ul>
 *   <li>{@link #findAll()}.</li>
 *   <li>{@link #findById(int)}.</li>
 *   <li>{@link #insert(Device)} — returns the generated key.</li>
 * </ul>
 *
 * <p>{@link #findById(int)} returns an {@link Optional} rather than {@code null} because a
 * miss is an expected outcome, not a fault: a meter can connect claiming a device id that
 * was never seeded, and the server has to reject that connection rather than dereference
 * whatever came back.</p>
 *
 * <p>Syllabus mapping: Unit III — Database connectivity via JDBC.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class DeviceDao {

    private static final String SQL_FIND_ALL = """
            SELECT device_id, name, appliance_type, location, rated_voltage, rated_power_watts
              FROM devices
             ORDER BY device_id
            """;

    private static final String SQL_FIND_BY_ID = """
            SELECT device_id, name, appliance_type, location, rated_voltage, rated_power_watts
              FROM devices
             WHERE device_id = ?
            """;

    private static final String SQL_INSERT = """
            INSERT INTO devices (name, appliance_type, location, rated_voltage, rated_power_watts)
            VALUES (?, ?, ?, ?, ?)
            """;

    private final ConnectionFactory connections;

    /**
     * @param connections factory this DAO takes its connections from; must not be null
     * @throws NullPointerException if {@code connections} is null
     */
    public DeviceDao(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Returns the whole device catalogue, ordered by id.
     *
     * @return every device; empty if the catalogue has not been seeded
     * @throws DataAccessException if the query fails
     */
    public List<Device> findAll() {
        try (Connection c = connections.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_FIND_ALL);
             ResultSet rs = ps.executeQuery()) {

            List<Device> devices = new ArrayList<>();
            while (rs.next()) {
                devices.add(mapRow(rs));
            }
            return devices;
        } catch (SQLException e) {
            throw new DataAccessException("failed to load the device catalogue", e);
        }
    }

    /**
     * Looks up one device by its surrogate key.
     *
     * @param deviceId the key to look for
     * @return the device, or {@link Optional#empty()} if no row has that id
     * @throws DataAccessException if the query fails
     */
    public Optional<Device> findById(int deviceId) {
        try (Connection c = connections.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_ID)) {

            ps.setInt(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("failed to load device " + deviceId, e);
        }
    }

    /**
     * Registers a new device.
     *
     * <p>The {@code deviceId} carried by the argument is ignored — the database assigns it.
     * Build the argument with {@link Device#unsaved} to make that explicit.</p>
     *
     * @param device the device to register; must not be null
     * @return the generated {@code device_id}
     * @throws DataAccessException  if the insert fails, including on a duplicate name
     * @throws NullPointerException if {@code device} is null
     */
    public int insert(Device device) {
        Objects.requireNonNull(device, "device");
        try (Connection c = connections.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_INSERT, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, device.getName());
            ps.setString(2, device.getApplianceType());
            ps.setString(3, device.getLocation());
            ps.setDouble(4, device.getRatedVoltage());
            ps.setDouble(5, device.getRatedPowerWatts());
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
                throw new DataAccessException("insert of device '" + device.getName()
                        + "' returned no generated key");
            }
        } catch (SQLException e) {
            throw new DataAccessException("failed to insert device '" + device.getName() + "'", e);
        }
    }

    /** Maps the current row of a result set selecting the six device columns. */
    private static Device mapRow(ResultSet rs) throws SQLException {
        return new Device(
                rs.getInt("device_id"),
                rs.getString("name"),
                rs.getString("appliance_type"),
                rs.getString("location"),
                rs.getDouble("rated_voltage"),
                rs.getDouble("rated_power_watts"));
    }
}
