package com.smarthome.energy.model;

/**
 * Value object describing a detection threshold for one metric on one device.
 *
 * <p>Mirrors a row of the {@code thresholds} table. A threshold carries an optional
 * lower bound and an optional upper bound for a {@link Metric}. The rule engine reads
 * these bounds to decide whether a {@link Reading} is anomalous. A {@code null}
 * {@code deviceId} denotes a default that applies to every device that lacks a specific
 * override.</p>
 *
 * <p>Intended fields:
 * <ul>
 *   <li>{@code deviceId} — target device, or {@code null} for the global default.</li>
 *   <li>{@code metric} — which quantity this threshold constrains ({@link Metric}).</li>
 *   <li>{@code minValue} — lower bound; a reading below it is a sag/under-condition.</li>
 *   <li>{@code maxValue} — upper bound; a reading above it is a spike/overload.</li>
 * </ul>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals; Unit III — JDBC entity.</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public final class Threshold {
    // Placeholder — fields, constructor, and accessors implemented by the author.
}
