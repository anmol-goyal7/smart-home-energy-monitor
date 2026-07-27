package com.smarthome.energy.model;

/**
 * Severity classification attached to every {@link Event}.
 *
 * <p>Severity is derived by a {@code DetectionRule} from how far a reading has crossed
 * its {@link Threshold}: a small excursion is a {@code WARNING}, a large one is
 * {@code CRITICAL}. It drives dashboard colour coding and alert prioritisation.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (enums).</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public enum Severity {

    /** Informational; recorded but not alarming. */
    INFO,

    /** Threshold crossed by a modest margin. */
    WARNING,

    /** Threshold crossed by a large margin; needs attention. */
    CRITICAL
}
