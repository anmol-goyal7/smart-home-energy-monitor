package com.smarthome.energy.db;

/**
 * Data access object for the {@code devices} table.
 *
 * <p>Provides read access to the relatively static device catalogue and the ability to
 * register a new device. The server loads the device list at start-up so it can resolve a
 * meter's {@code deviceId} to its rated values, and the dashboard uses it to label panels.</p>
 *
 * <p>Intended API:
 * <ul>
 *   <li>{@code List<Device> findAll()}.</li>
 *   <li>{@code Optional<Device> findById(int deviceId)}.</li>
 *   <li>{@code int insert(Device d)} — returns the generated key.</li>
 * </ul>
 *
 * <p>Syllabus mapping: Unit III — Database connectivity via JDBC.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class DeviceDao {
    // Placeholder — SQL and result-set mapping implemented by the author.
}
