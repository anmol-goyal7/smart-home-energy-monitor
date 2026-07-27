package com.smarthome.energy.model;

/**
 * Value object representing a power-quality event raised by the rule engine.
 *
 * <p>An {@code Event} records that a specific {@link Reading} violated a
 * {@link Threshold} — a voltage spike, a voltage sag, or a load overload. Events are
 * persisted to the {@code events} table and surfaced on the dashboard's alert log.</p>
 *
 * <p>Intended fields:
 * <ul>
 *   <li>{@code deviceId} — the device that produced the offending reading.</li>
 *   <li>{@code triggeringReadingId} — the reading that tripped the rule (nullable).</li>
 *   <li>{@code type} — see {@link EventType}.</li>
 *   <li>{@code severity} — see {@link Severity}.</li>
 *   <li>{@code measuredValue} — the value observed.</li>
 *   <li>{@code thresholdValue} — the limit that was crossed.</li>
 *   <li>{@code detectedAt} — server-side detection timestamp.</li>
 * </ul>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals.</p>
 *
 * @author Jiya Nambiar (meter simulators & rule engine)
 */
public final class Event {
    // Placeholder — fields, constructor, and accessors implemented by the author.
}
