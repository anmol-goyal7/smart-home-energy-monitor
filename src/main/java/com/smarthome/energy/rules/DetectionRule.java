package com.smarthome.energy.rules;

import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.Reading;
import com.smarthome.energy.model.Severity;

import java.util.Optional;

/**
 * Strategy interface implemented by every power-quality detection rule.
 *
 * <p>Modelling each rule as its own implementation of this interface keeps the detection
 * logic open for extension: adding a new kind of alert means adding a new class, not
 * editing the engine. The {@link RuleEngine} holds a list of {@code DetectionRule}s and
 * applies each one to every reading.</p>
 *
 * <p>Implementations must be pure and thread-safe: the engine evaluates readings on the
 * dispatcher's worker threads, and one rule instance serves every meter. In practice that
 * means holding no state at all — everything a rule needs arrives in its two arguments.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (strategy pattern, interfaces).</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
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

    /** @return a short name for this rule, used in the engine's diagnostics. */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * Grades an excursion by how far past the limit it went, as a fraction of the limit.
     *
     * <p><strong>Why a fraction rather than an absolute margin.</strong> One rule bounds a
     * router at 40 W and a water heater at 3300 W; "50 W over" is a fault on the first and
     * noise on the second, while "10% over" means the same thing on both.</p>
     *
     * <p><strong>Why each rule brings its own cut-offs.</strong> Voltage and power are not
     * comparable on this scale: a supply drifting 5% out of band is a serious event, while a
     * motor drawing 5% over its rating on start-up is not. Each rule declares the two
     * fractions that are meaningful for its metric and this method only applies them, so the
     * policy stays visible in the rule that owns it and the arithmetic exists once.</p>
     *
     * @param measured         the observed value
     * @param limit            the limit it crossed
     * @param warningFraction  relative excursion at or above which the event is a WARNING
     * @param criticalFraction relative excursion at or above which the event is CRITICAL
     * @return the severity that excursion earns
     */
    static Severity severityFor(double measured, double limit, double warningFraction,
                                double criticalFraction) {
        // A limit of zero makes the fraction meaningless: anything at all past it is as far
        // past as the scale can express, so it is graded at the top rather than dividing by 0.
        if (limit == 0.0) {
            return Severity.CRITICAL;
        }
        double excursion = Math.abs(measured - limit) / Math.abs(limit);
        if (excursion >= criticalFraction) {
            return Severity.CRITICAL;
        }
        if (excursion >= warningFraction) {
            return Severity.WARNING;
        }
        return Severity.INFO;
    }
}
