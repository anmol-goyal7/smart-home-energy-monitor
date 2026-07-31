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
 * Detects an under-voltage condition (a sag).
 *
 * <p>Fires when a reading's voltage falls below the lower bound of the {@code VOLTAGE}
 * threshold for its device. The margin below the bound sets the {@code Severity}. Produces
 * a {@code VOLTAGE_SAG} event, the counterpart to {@link VoltageSpikeRule}.</p>
 *
 * <p>It reads the {@code min_value} of the same threshold row the spike rule reads the
 * {@code max_value} of, so one row bounds the supply band from both sides and an operator
 * editing "the voltage limits for the fridge" edits one thing. The severity cut-offs match
 * the spike rule's for the same reason they are tight there: this is a metric where a few
 * percent matters.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (strategy pattern).</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public final class VoltageSagRule implements DetectionRule {

    /** Relative shortfall at or above which a sag stops being noise. */
    private static final double WARNING_FRACTION = 0.01;

    /** Relative shortfall at or above which a sag is a supply fault. */
    private static final double CRITICAL_FRACTION = 0.04;

    @Override
    public Optional<Event> evaluate(Reading reading, RuleContext context) {
        Optional<Threshold> bound = context.thresholdFor(reading.getDeviceId(), Metric.VOLTAGE);
        if (bound.isEmpty() || !bound.get().isBelowMin(reading.getVoltage())) {
            return Optional.empty();
        }

        double limit = bound.get().getMinValue();
        double measured = reading.getVoltage();
        Severity severity = DetectionRule.severityFor(measured, limit, WARNING_FRACTION, CRITICAL_FRACTION);

        return Optional.of(Event.raisedNow(reading.getDeviceId(), null, EventType.VOLTAGE_SAG,
                severity, measured, limit,
                String.format(Locale.ROOT, "%s: supply at %.2f V, below the %.2f V floor",
                        context.deviceName(reading.getDeviceId()), measured, limit)));
    }
}
