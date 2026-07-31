package com.smarthome.energy.rules;

import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.EventType;
import com.smarthome.energy.model.Metric;
import com.smarthome.energy.model.Reading;
import com.smarthome.energy.model.Severity;
import com.smarthome.energy.model.Threshold;

import java.util.Locale;
import java.util.Optional;

/**
 * Detects a load overload condition.
 *
 * <p>Fires when a reading's real power exceeds the upper bound of the {@code POWER}
 * threshold for its device (typically derived from the device's rated wattage plus a
 * tolerance). The overshoot above the limit sets the {@code Severity}. Produces a
 * {@code LOAD_OVERLOAD} event.</p>
 *
 * <p>The cut-offs are wider than the voltage rules': the seeded ceilings already include a
 * start-up allowance, and a motor that draws a few percent over its rating for a second is
 * a normal appliance, not an incident. A refrigerator at 540 W against a 500 W ceiling is a
 * {@code WARNING}; it takes 20% over the ceiling to be {@code CRITICAL}.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (strategy pattern).</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public final class LoadOverloadRule implements DetectionRule {

    /** Relative overshoot at or above which a draw stops being start-up headroom. */
    private static final double WARNING_FRACTION = 0.02;

    /** Relative overshoot at or above which a draw is an overload proper. */
    private static final double CRITICAL_FRACTION = 0.20;

    @Override
    public Optional<Event> evaluate(Reading reading, RuleContext context) {
        Optional<Threshold> bound = context.thresholdFor(reading.getDeviceId(), Metric.POWER);
        if (bound.isEmpty() || !bound.get().isAboveMax(reading.getPowerWatts())) {
            return Optional.empty();
        }

        double limit = bound.get().getMaxValue();
        double measured = reading.getPowerWatts();
        Severity severity = DetectionRule.severityFor(measured, limit, WARNING_FRACTION, CRITICAL_FRACTION);

        return Optional.of(Event.raisedNow(reading.getDeviceId(), null, EventType.LOAD_OVERLOAD,
                severity, measured, limit,
                String.format(Locale.ROOT, "%s: drawing %.2f W against a %.2f W ceiling",
                        context.deviceName(reading.getDeviceId()), measured, limit)));
    }
}
