package com.smarthome.energy.rules;

import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.Reading;

import java.util.Optional;

/**
 * Detects a load overload condition.
 *
 * <p>Fires when a reading's real power exceeds the upper bound of the {@code POWER}
 * threshold for its device (typically derived from the device's rated wattage plus a
 * tolerance). The overshoot above the limit sets the {@code Severity}. Produces a
 * {@code LOAD_OVERLOAD} event.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (strategy pattern).</p>
 *
 * @author Team member 3 (meter simulators & rule engine)
 */
public final class LoadOverloadRule implements DetectionRule {

    @Override
    public Optional<Event> evaluate(Reading reading, RuleContext context) {
        // Placeholder — overload detection implemented by the author.
        throw new UnsupportedOperationException("not yet implemented");
    }
}
