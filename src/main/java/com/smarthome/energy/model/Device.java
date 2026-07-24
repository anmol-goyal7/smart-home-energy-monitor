package com.smarthome.energy.model;

/**
 * Value object describing a monitored appliance (one physical smart meter).
 *
 * <p>Mirrors a row of the {@code devices} table. Devices are relatively static
 * reference data: they are seeded once and referenced by every {@link Reading} and
 * {@link Event} through {@code deviceId}.</p>
 *
 * <p>Intended fields:
 * <ul>
 *   <li>{@code deviceId} — surrogate primary key.</li>
 *   <li>{@code name} — human-readable label (e.g. "Kitchen Refrigerator").</li>
 *   <li>{@code applianceType} — category used for grouping/analytics.</li>
 *   <li>{@code location} — room or circuit.</li>
 *   <li>{@code ratedVoltage} — nominal operating voltage.</li>
 *   <li>{@code ratedPowerWatts} — manufacturer power rating, a baseline for overload rules.</li>
 * </ul>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals; Unit III — maps to a JDBC entity.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class Device {
    // Placeholder — fields, constructor, and accessors implemented by the author.
}
