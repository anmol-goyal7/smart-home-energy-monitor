package com.smarthome.energy.model;

/**
 * Immutable value object representing a single power reading emitted by one smart
 * meter for one appliance at one instant.
 *
 * <p>A {@code Reading} is the atomic unit that flows through the whole system: it is
 * produced by a {@code MeterSimulator}, serialized onto the wire, validated by the
 * protocol layer, parsed back into this object on the server, persisted by
 * {@code ReadingDao}, evaluated by the {@code RuleEngine}, and finally rendered on the
 * dashboard.</p>
 *
 * <p>Intended fields (the author implements accessors/constructor):
 * <ul>
 *   <li>{@code deviceId} — foreign key to the emitting device.</li>
 *   <li>{@code readingTimestamp} — epoch millis of the measurement at the meter.</li>
 *   <li>{@code voltage} — RMS volts.</li>
 *   <li>{@code current} — RMS amperes.</li>
 *   <li>{@code powerWatts} — real power in watts.</li>
 * </ul>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (encapsulation, immutability).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class Reading {
    // Placeholder — fields, constructor, and accessors implemented by the author.
}
