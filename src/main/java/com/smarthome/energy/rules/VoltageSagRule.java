package com.smarthome.energy.rules;

import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.Reading;

import java.util.Optional;

/**
 * Detects an under-voltage condition (a sag).
 *
 * <p>Fires when a reading's voltage falls below the lower bound of the {@code VOLTAGE}
 * threshold for its device. The margin below the bound sets the {@code Severity}. Produces
 * a {@code VOLTAGE_SAG} event, the counterpart to {@link VoltageSpikeRule}.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (strategy pattern).</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public final class VoltageSagRule implements DetectionRule {

    @Override
    public Optional<Event> evaluate(Reading reading, RuleContext context) {
        // Placeholder — sag detection implemented by the author.
        throw new UnsupportedOperationException("not yet implemented");
    }
}
