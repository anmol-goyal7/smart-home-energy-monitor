package com.smarthome.energy.simulator;

/**
 * Produces the numeric values a {@link MeterSimulator} emits each tick.
 *
 * <p>Given an {@link ApplianceProfile}, generates a voltage/current/power sample that
 * jitters realistically around the appliance's nominal band, and, with a small
 * configurable probability, injects an anomaly (voltage spike, voltage sag, or power
 * overload) so the downstream rule engine has something to catch.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals.</p>
 *
 * @author Jiya Nambiar (meter simulators & rule engine)
 */
public final class WaveformGenerator {
    // Placeholder — sampling and anomaly injection implemented by the author.
}
