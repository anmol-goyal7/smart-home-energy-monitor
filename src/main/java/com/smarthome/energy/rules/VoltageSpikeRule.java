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
 * Detects an over-voltage condition (a spike).
 *
 * <p>Fires when a reading's voltage exceeds the upper bound of the {@code VOLTAGE}
 * threshold for its device. The margin above the bound sets the {@code Severity}: a small
 * overshoot is a {@code WARNING}, a large one is {@code CRITICAL}. Produces a
 * {@code VOLTAGE_SPIKE} event.</p>
 *
 * <p>With the seeded band of 207–253 V, 256 V is a {@code WARNING} and 264 V is
 * {@code CRITICAL}. The cut-offs are tight because they are voltage cut-offs: mains supply
 * is specified to stay within about ±10% of nominal, so a reading 4% past the end of that
 * band is not a rounding error, it is a supply fault.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (strategy pattern).</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public final class VoltageSpikeRule implements DetectionRule {

    /** Relative overshoot at or above which a spike stops being noise. */
    private static final double WARNING_FRACTION = 0.01;

    /** Relative overshoot at or above which a spike is a supply fault. */
    private static final double CRITICAL_FRACTION = 0.04;

    @Override
    public Optional<Event> evaluate(Reading reading, RuleContext context) {
        Optional<Threshold> bound = context.thresholdFor(reading.getDeviceId(), Metric.VOLTAGE);
        if (bound.isEmpty() || !bound.get().isAboveMax(reading.getVoltage())) {
            return Optional.empty();
        }

        double limit = bound.get().getMaxValue();
        double measured = reading.getVoltage();
        Severity severity = DetectionRule.severityFor(measured, limit, WARNING_FRACTION, CRITICAL_FRACTION);

        return Optional.of(Event.raisedNow(reading.getDeviceId(), null, EventType.VOLTAGE_SPIKE,
                severity, measured, limit,
                String.format(Locale.ROOT, "%s: supply at %.2f V, above the %.2f V ceiling",
                        context.deviceName(reading.getDeviceId()), measured, limit)));
    }
}
