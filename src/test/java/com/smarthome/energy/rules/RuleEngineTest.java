package com.smarthome.energy.rules;

import com.smarthome.energy.model.Device;
import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.EventType;
import com.smarthome.energy.model.Metric;
import com.smarthome.energy.model.Reading;
import com.smarthome.energy.model.Threshold;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the engine itself rather than the rules: that every rule sees every reading, that
 * the events come back bound to the reading that caused them, that a reloaded context takes
 * effect on the next reading, and that one broken rule cannot silence the others.
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
class RuleEngineTest {

    private static final int FRIDGE = 1;

    private static RuleContext context(double maxVolts, double maxWatts) {
        return new RuleContext(
                List.of(new Device(FRIDGE, "Kitchen Refrigerator", "REFRIGERATOR", "Kitchen", 230.0, 350.0)),
                List.of(
                        new Threshold(null, Metric.VOLTAGE, 207.0, maxVolts, "supply band"),
                        new Threshold(FRIDGE, Metric.POWER, null, maxWatts, "overload ceiling")));
    }

    private static Reading reading(double volts, double watts) {
        return new Reading(FRIDGE, Instant.ofEpochMilli(1_721_817_600_000L), volts, watts / volts, watts);
    }

    @Test
    @DisplayName("a nominal reading raises nothing but is still counted as evaluated")
    void nominalReadingRaisesNothing() {
        RuleEngine engine = new RuleEngine(context(253.0, 500.0));

        assertTrue(engine.evaluate(reading(230.0, 180.0), 7L).isEmpty());
        assertEquals(1, engine.getEvaluatedCount());
        assertEquals(0, engine.getRaisedCount());
    }

    @Test
    @DisplayName("one reading can trip two rules, and both events are raised")
    void independentRulesBothFire() {
        RuleEngine engine = new RuleEngine(context(253.0, 500.0));

        // 190 V and 620 W: a sag and an overload in the same reading.
        List<Event> raised = engine.evaluate(reading(190.0, 620.0), 42L);

        assertEquals(2, raised.size(), () -> "expected a sag and an overload, got " + raised);
        assertTrue(raised.stream().anyMatch(e -> e.getType() == EventType.VOLTAGE_SAG));
        assertTrue(raised.stream().anyMatch(e -> e.getType() == EventType.LOAD_OVERLOAD));
        assertEquals(2, engine.getRaisedCount());
    }

    @Test
    @DisplayName("events come back bound to the reading id the insert generated")
    void eventsCarryTheReadingId() {
        RuleEngine engine = new RuleEngine(context(253.0, 500.0));

        Event bound = engine.evaluate(reading(264.0, 180.0), 99L).get(0);
        assertEquals(99L, bound.getTriggeringReadingId());

        // The same reading evaluated without persistence carries no key, because there is none.
        Event unbound = engine.evaluate(reading(264.0, 180.0)).get(0);
        assertNull(unbound.getTriggeringReadingId());
    }

    @Test
    @DisplayName("a reloaded context changes what alerts on the very next reading")
    void reloadTakesEffectImmediately() {
        RuleEngine engine = new RuleEngine(context(253.0, 500.0));
        Reading borderline = reading(230.0, 540.0);

        assertEquals(1, engine.evaluate(borderline, 1L).size(), "540 W is over the 500 W ceiling");

        // What the Phase 4 threshold editor does: raise the ceiling and reload.
        engine.reload(context(253.0, 560.0));
        assertTrue(engine.evaluate(borderline, 2L).isEmpty(), "the same reading is legal under 560 W");
    }

    @Test
    @DisplayName("a rule that throws costs its own alerts and nothing else")
    void oneBrokenRuleDoesNotSilenceTheRest() {
        DetectionRule broken = (reading, context) -> {
            throw new IllegalStateException("deliberately broken rule");
        };
        RuleEngine engine = new RuleEngine(context(253.0, 500.0),
                List.of(broken, new VoltageSpikeRule()));

        List<Event> raised = engine.evaluate(reading(264.0, 180.0), 5L);

        assertEquals(1, raised.size(), "the working rule still fired");
        assertEquals(EventType.VOLTAGE_SPIKE, raised.get(0).getType());
    }

    @Test
    @DisplayName("an engine with no rules is refused rather than silently detecting nothing")
    void emptyRuleListIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuleEngine(context(253.0, 500.0), List.of()));
    }

    @Test
    @DisplayName("the shipped engine carries the three rules the README documents")
    void defaultRuleSetIsTheDocumentedOne() {
        List<String> names = new RuleEngine(context(253.0, 500.0)).getRules().stream()
                .map(DetectionRule::name)
                .toList();

        assertEquals(List.of("VoltageSpikeRule", "VoltageSagRule", "LoadOverloadRule"), names);
    }

    @Test
    @DisplayName("a rule can be tested in isolation because it is only a function of its arguments")
    void rulesAreIndependentOfTheEngine() {
        Optional<Event> fired = new VoltageSpikeRule().evaluate(reading(264.0, 180.0), context(253.0, 500.0));
        assertTrue(fired.isPresent());
    }
}
