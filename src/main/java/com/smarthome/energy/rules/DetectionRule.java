package com.smarthome.energy.rules;

import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.Reading;

import java.util.Optional;

/**
 * Strategy interface implemented by every power-quality detection rule.
 *
 * <p>Modelling each rule as its own implementation of this interface keeps the detection
 * logic open for extension: adding a new kind of alert means adding a new class, not
 * editing the engine. The {@link RuleEngine} holds a list of {@code DetectionRule}s and
 * applies each one to every reading.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (strategy pattern, interfaces).</p>
 *
 * @author Team member 3 (meter simulators & rule engine)
 */
public interface DetectionRule {

    /**
     * Evaluate a single reading against this rule's thresholds.
     *
     * @param reading the reading to inspect
     * @param context the current thresholds and device metadata
     * @return an {@link Event} if the rule fired, otherwise {@code Optional.empty()}
     */
    Optional<Event> evaluate(Reading reading, RuleContext context);
}
