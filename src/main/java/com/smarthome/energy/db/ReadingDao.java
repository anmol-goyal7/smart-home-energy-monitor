package com.smarthome.energy.db;

/**
 * Data access object for the {@code readings} table.
 *
 * <p>Owns all SQL for persisting and querying {@code Reading} rows. Inserts are performed
 * with a {@code PreparedStatement} on the ingest path; range queries (by device and time
 * window) back the dashboard's history charts and feed the Python analytics module's
 * source data.</p>
 *
 * <p>Intended API:
 * <ul>
 *   <li>{@code void insert(Reading r)} — parameterised insert of one reading.</li>
 *   <li>{@code List<Reading> findByDeviceSince(int deviceId, long sinceMillis)}.</li>
 * </ul>
 *
 * <p>Syllabus mapping: Unit III — Database connectivity via JDBC (PreparedStatement, CRUD).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class ReadingDao {
    // Placeholder — SQL and PreparedStatement handling implemented by the author.
}
