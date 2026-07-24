package com.smarthome.energy.model;

/**
 * The measurable quantities carried by a {@link Reading} that a {@link Threshold} can
 * constrain and a {@code DetectionRule} can evaluate.
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (enums).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public enum Metric {

    /** RMS voltage, in volts. */
    VOLTAGE,

    /** RMS current, in amperes. */
    CURRENT,

    /** Real power, in watts. */
    POWER
}
