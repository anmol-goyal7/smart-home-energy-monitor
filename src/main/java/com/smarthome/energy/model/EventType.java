package com.smarthome.energy.model;

/**
 * Enumeration of the power-quality conditions the rule engine can detect.
 *
 * <p>Each constant corresponds to exactly one {@code DetectionRule} implementation and
 * is stored verbatim in the {@code events.event_type} column.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (enums).</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public enum EventType {

    /** Measured voltage exceeded the upper threshold. */
    VOLTAGE_SPIKE,

    /** Measured voltage fell below the lower threshold. */
    VOLTAGE_SAG,

    /** Real power draw exceeded the device's power limit. */
    LOAD_OVERLOAD
}
