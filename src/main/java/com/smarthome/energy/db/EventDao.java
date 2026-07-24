package com.smarthome.energy.db;

/**
 * Data access object for the {@code events} table.
 *
 * <p>Persists the power-quality {@code Event}s raised by the rule engine and queries them
 * back for the dashboard's alert log. Each insert links the event to its device and, when
 * known, to the triggering reading.</p>
 *
 * <p>Intended API:
 * <ul>
 *   <li>{@code void insert(Event e)}.</li>
 *   <li>{@code List<Event> findRecent(int limit)} — newest alerts first.</li>
 *   <li>{@code List<Event> findByDevice(int deviceId)}.</li>
 * </ul>
 *
 * <p>Syllabus mapping: Unit III — Database connectivity via JDBC.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class EventDao {
    // Placeholder — SQL and result-set mapping implemented by the author.
}
