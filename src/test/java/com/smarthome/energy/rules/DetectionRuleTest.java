package com.smarthome.energy.rules;

import com.smarthome.energy.model.Device;
import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.EventType;
import com.smarthome.energy.model.Metric;
import com.smarthome.energy.model.Reading;
import com.smarthome.energy.model.Severity;
import com.smarthome.energy.model.Threshold;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the three detection rules and the context they read their limits from: which
 * readings fire them, which do not, how the severity bands grade an excursion, and how a
 * device-specific threshold overrides the global default.
 *
 * <p>The numbers are the seeded ones from {@code sql/seed.sql} — a 207–253 V supply band and
 * a 500 W refrigerator ceiling — so the assertions are about the system as it actually runs,
 * and the README's worked examples are checked rather than asserted.</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
class DetectionRuleTest {

    private static final int FRIDGE = 1;
    private static final int UNCONFIGURED = 9;

    /** The seeded catalogue and limits, as the server loads them at start-up. */
    private static RuleContext seededContext() {
        return new RuleContext(
                List.of(new Device(FRIDGE, "Kitchen Refrigerator", "REFRIGERATOR", "Kitchen", 230.0, 350.0)),
                List.of(
                        new Threshold(null, Metric.VOLTAGE, 207.0, 253.0, "Default supply band"),
                        new Threshold(FRIDGE, Metric.POWER, null, 500.0, "Refrigerator overload ceiling")));
    }

    private static Reading reading(int deviceId, double volts, double watts) {
        return new Reading(deviceId, Instant.ofEpochMilli(1_721_817_600_000L), volts,
                watts / volts, watts);
    }

    // ------------------------------------------------------------------ voltage spike

    @Test
    @DisplayName("a reading inside the supply band raises nothing")
    void nominalVoltageDoesNotFire() {
        RuleContext context = seededContext();
        Reading nominal = reading(FRIDGE, 230.0, 180.0);

        assertTrue(new VoltageSpikeRule().evaluate(nominal, context).isEmpty());
        assertTrue(new VoltageSagRule().evaluate(nominal, context).isEmpty());
        assertTrue(new LoadOverloadRule().evaluate(nominal, context).isEmpty());
    }

    @Test
    @DisplayName("262 V raises a VOLTAGE_SPIKE carrying the value and the limit it crossed")
    void spikeFiresAboveTheCeiling() {
        Optional<Event> fired = new VoltageSpikeRule().evaluate(reading(FRIDGE, 262.0, 180.0), seededContext());

        assertTrue(fired.isPresent(), "262 V is above the seeded 253 V ceiling");
        Event event = fired.get();
        assertEquals(EventType.VOLTAGE_SPIKE, event.getType());
        assertEquals(FRIDGE, event.getDeviceId());
        assertEquals(262.0, event.getMeasuredValue(), 1e-9);
        assertEquals(253.0, event.getThresholdValue(), 1e-9);
        assertTrue(event.getDetail().contains("Kitchen Refrigerator"),
                "the detail names the device an operator would look for: " + event.getDetail());
    }

    @Test
    @DisplayName("a value exactly on the limit is not an excursion")
    void theLimitItselfIsLegal() {
        RuleContext context = seededContext();
        assertTrue(new VoltageSpikeRule().evaluate(reading(FRIDGE, 253.0, 180.0), context).isEmpty());
        assertTrue(new VoltageSagRule().evaluate(reading(FRIDGE, 207.0, 180.0), context).isEmpty());
        assertTrue(new LoadOverloadRule().evaluate(reading(FRIDGE, 230.0, 500.0), context).isEmpty());
    }

    @Test
    @DisplayName("spike severity rises with the excursion: 256 V warns, 264 V is critical")
    void spikeSeverityFollowsTheExcursion() {
        RuleContext context = seededContext();
        VoltageSpikeRule rule = new VoltageSpikeRule();

        assertEquals(Severity.WARNING,
                rule.evaluate(reading(FRIDGE, 256.0, 180.0), context).orElseThrow().getSeverity());
        assertEquals(Severity.CRITICAL,
                rule.evaluate(reading(FRIDGE, 264.0, 180.0), context).orElseThrow().getSeverity());
    }

    // ------------------------------------------------------------------ voltage sag

    @Test
    @DisplayName("198 V raises a VOLTAGE_SAG against the lower bound of the same row")
    void sagFiresBelowTheFloor() {
        Event event = new VoltageSagRule().evaluate(reading(FRIDGE, 198.0, 180.0), seededContext())
                .orElseThrow();

        assertEquals(EventType.VOLTAGE_SAG, event.getType());
        assertEquals(198.0, event.getMeasuredValue(), 1e-9);
        assertEquals(207.0, event.getThresholdValue(), 1e-9);
        assertEquals(Severity.CRITICAL, event.getSeverity(), "198 V is 4.3% below the floor");
    }

    @Test
    @DisplayName("a spike is not a sag and a sag is not a spike")
    void theVoltageRulesDoNotOverlap() {
        RuleContext context = seededContext();
        assertTrue(new VoltageSagRule().evaluate(reading(FRIDGE, 262.0, 180.0), context).isEmpty());
        assertTrue(new VoltageSpikeRule().evaluate(reading(FRIDGE, 198.0, 180.0), context).isEmpty());
    }

    // ------------------------------------------------------------------ load overload

    @Test
    @DisplayName("540 W against a 500 W ceiling is a LOAD_OVERLOAD, and a WARNING not a CRITICAL")
    void overloadFiresAboveTheCeiling() {
        Event event = new LoadOverloadRule().evaluate(reading(FRIDGE, 230.0, 540.0), seededContext())
                .orElseThrow();

        assertEquals(EventType.LOAD_OVERLOAD, event.getType());
        assertEquals(540.0, event.getMeasuredValue(), 1e-9);
        assertEquals(500.0, event.getThresholdValue(), 1e-9);
        assertEquals(Severity.WARNING, event.getSeverity(), "8% over a ceiling that includes headroom");
    }

    @Test
    @DisplayName("a fifth over the ceiling is critical")
    void largeOverloadIsCritical() {
        Event event = new LoadOverloadRule().evaluate(reading(FRIDGE, 230.0, 625.0), seededContext())
                .orElseThrow();
        assertEquals(Severity.CRITICAL, event.getSeverity());
    }

    // ------------------------------------------------------------------ the context

    @Test
    @DisplayName("a device-specific threshold overrides the global default for that metric")
    void deviceThresholdWinsOverTheDefault() {
        RuleContext context = new RuleContext(
                List.of(new Device(FRIDGE, "Kitchen Refrigerator", "REFRIGERATOR", "Kitchen", 230.0, 350.0)),
                List.of(
                        new Threshold(null, Metric.VOLTAGE, 207.0, 253.0, "Default supply band"),
                        new Threshold(FRIDGE, Metric.VOLTAGE, 220.0, 240.0, "Sensitive electronics")));

        assertEquals(240.0, context.thresholdFor(FRIDGE, Metric.VOLTAGE).orElseThrow().getMaxValue());
        assertEquals(253.0, context.thresholdFor(UNCONFIGURED, Metric.VOLTAGE).orElseThrow().getMaxValue());

        // 245 V is legal under the default and a spike under the override.
        assertTrue(new VoltageSpikeRule().evaluate(reading(FRIDGE, 245.0, 180.0), context).isPresent());
        assertTrue(new VoltageSpikeRule().evaluate(reading(UNCONFIGURED, 245.0, 180.0), context).isEmpty());
    }

    @Test
    @DisplayName("a metric with no threshold at all raises nothing")
    void anUnboundedMetricNeverFires() {
        RuleContext context = new RuleContext(List.of(), List.of());

        // 400 V drawing 9 kW: absurd, and still silent, because nobody said what normal is.
        Reading absurd = reading(FRIDGE, 400.0, 9_000.0);
        assertTrue(new VoltageSpikeRule().evaluate(absurd, context).isEmpty());
        assertTrue(new VoltageSagRule().evaluate(absurd, context).isEmpty());
        assertTrue(new LoadOverloadRule().evaluate(absurd, context).isEmpty());
        assertFalse(context.thresholdFor(FRIDGE, Metric.VOLTAGE).isPresent());
    }

    @Test
    @DisplayName("a threshold bounding only one side leaves the other unbounded")
    void oneSidedThresholdsFireOnOneSideOnly() {
        RuleContext context = seededContext();
        // The seeded POWER row has a max and no min, so nothing can be "under-power".
        assertTrue(context.thresholdFor(FRIDGE, Metric.POWER).orElseThrow().hasMax());
        assertFalse(context.thresholdFor(FRIDGE, Metric.POWER).orElseThrow().hasMin());
    }

    @Test
    @DisplayName("the context reports what it loaded, which is what the server logs at start-up")
    void contextCountsItsContents() {
        RuleContext context = seededContext();
        assertEquals(1, context.getDeviceCount());
        assertEquals(2, context.getThresholdCount());
        assertEquals("Kitchen Refrigerator", context.deviceName(FRIDGE));
        assertEquals("device 9", context.deviceName(UNCONFIGURED), "an uncatalogued device still names itself");
    }
}
