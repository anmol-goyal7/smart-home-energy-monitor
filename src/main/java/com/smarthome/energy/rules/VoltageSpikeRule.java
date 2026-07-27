package com.smarthome.energy.rules;

import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.Reading;

import java.util.Optional;

/**
 * Detects an over-voltage condition (a spike).
 *
 * <p>Fires when a reading's voltage exceeds the upper bound of the {@code VOLTAGE}
 * threshold for its device. The margin above the bound sets the {@code Severity}: a small
 * overshoot is a {@code WARNING}, a large one is {@code CRITICAL}. Produces a
 * {@code VOLTAGE_SPIKE} event.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (strategy pattern).</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public final class VoltageSpikeRule implements DetectionRule {

    @Override
    public Optional<Event> evaluate(Reading reading, RuleContext context) {
        // Placeholder — spike detection implemented by the author.
        throw new UnsupportedOperationException("not yet implemented");
    }
}
