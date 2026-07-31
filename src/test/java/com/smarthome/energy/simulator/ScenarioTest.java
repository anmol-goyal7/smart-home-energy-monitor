package com.smarthome.energy.simulator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the scripted timeline: that a step is in force from its offset until something
 * supersedes it, that a cue changes nothing, that one-shots are reported once, and that the
 * shipped {@code incident} scenario actually aims its faults past the seeded thresholds.
 *
 * <p>The last of those is the one worth having. A demonstration whose scripted "voltage
 * spike" happened to sit inside the supply band would replay perfectly, raise no alert, and
 * be discovered in front of an audience.</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
class ScenarioTest {

    /** The seeded global supply band from {@code sql/seed.sql}. */
    private static final double SAG_FLOOR = 207.0;
    private static final double SPIKE_CEILING = 253.0;

    /** The seeded overload ceiling for device 1, the refrigerator. */
    private static final double FRIDGE_CEILING = 500.0;

    @Test
    @DisplayName("a sustained step is in force from its offset onwards")
    void sustainedStepAppliesFromItsOffset() {
        Scenario scenario = new Scenario("t", "test", Duration.ofSeconds(60), List.of(
                new Scenario.Step(Duration.ofSeconds(10), Scenario.Kind.VOLTAGE, 264.0,
                        Set.of(2), "spike")));

        assertNull(scenario.sustainedStepFor(2, Duration.ofSeconds(9)),
                "the step should not be in force before its offset");
        assertNotNull(scenario.sustainedStepFor(2, Duration.ofSeconds(10)),
                "the step should be in force at its offset");
        assertNotNull(scenario.sustainedStepFor(2, Duration.ofSeconds(59)),
                "nothing supersedes it, so it should still be in force at the end");
    }

    @Test
    @DisplayName("a step naming no devices applies to every meter")
    void emptyDeviceSetMeansEveryMeter() {
        Scenario scenario = new Scenario("t", "test", Duration.ofSeconds(60), List.of(
                new Scenario.Step(Duration.ofSeconds(5), Scenario.Kind.VOLTAGE, 196.0,
                        Set.of(), "whole-house sag")));

        for (int deviceId = 1; deviceId <= 6; deviceId++) {
            assertNotNull(scenario.sustainedStepFor(deviceId, Duration.ofSeconds(5)),
                    "device " + deviceId + " should be sagging");
        }
    }

    @Test
    @DisplayName("a step naming devices leaves the others alone")
    void namedDevicesOnly() {
        Scenario scenario = new Scenario("t", "test", Duration.ofSeconds(60), List.of(
                new Scenario.Step(Duration.ofSeconds(5), Scenario.Kind.VOLTAGE, 264.0,
                        Set.of(2), "HVAC spike")));

        assertNotNull(scenario.sustainedStepFor(2, Duration.ofSeconds(5)));
        assertNull(scenario.sustainedStepFor(1, Duration.ofSeconds(5)),
                "device 1 was not named and should be unaffected");
    }

    @Test
    @DisplayName("NOMINAL clears whatever was in force")
    void nominalClearsTheActiveStep() {
        Scenario scenario = new Scenario("t", "test", Duration.ofSeconds(60), List.of(
                new Scenario.Step(Duration.ofSeconds(10), Scenario.Kind.VOLTAGE, 264.0,
                        Set.of(2), "spike"),
                new Scenario.Step(Duration.ofSeconds(20), Scenario.Kind.NOMINAL, 0,
                        Set.of(2), "recovery")));

        assertNotNull(scenario.sustainedStepFor(2, Duration.ofSeconds(15)));
        assertNull(scenario.sustainedStepFor(2, Duration.ofSeconds(20)),
                "the recovery should have cleared the spike");
    }

    @Test
    @DisplayName("a NOTE cue neither applies nor clears — it is for the audience, not the meters")
    void noteChangesNothing() {
        Scenario scenario = new Scenario("t", "test", Duration.ofSeconds(60), List.of(
                new Scenario.Step(Duration.ofSeconds(10), Scenario.Kind.POWER, 540.0,
                        Set.of(1), "overload"),
                new Scenario.Step(Duration.ofSeconds(20), Scenario.Kind.NOTE, 0,
                        Set.of(1), "edit the threshold now")));

        Scenario.Step inForce = scenario.sustainedStepFor(1, Duration.ofSeconds(25));
        assertNotNull(inForce, "the cue must not have cleared the overload — the whole point of "
                + "the demonstration is that only the operator's edit stops it");
        assertEquals(Scenario.Kind.POWER, inForce.kind());
        assertEquals(540.0, inForce.value());
    }

    @Test
    @DisplayName("one-shots are reported once due and never become sustained")
    void oneShotsAreReportedByIndex() {
        Scenario scenario = new Scenario("t", "test", Duration.ofSeconds(60), List.of(
                new Scenario.Step(Duration.ofSeconds(10), Scenario.Kind.CORRUPT, 0,
                        Set.of(3), "one damaged frame")));

        assertTrue(scenario.dueOneShots(3, Duration.ofSeconds(9)).isEmpty(),
                "not due yet");
        assertEquals(List.of(0), scenario.dueOneShots(3, Duration.ofSeconds(10)));
        assertTrue(scenario.dueOneShots(1, Duration.ofSeconds(30)).isEmpty(),
                "device 1 was not named");
        assertNull(scenario.sustainedStepFor(3, Duration.ofSeconds(30)),
                "a corruption is an instant, not a state");
    }

    @Test
    @DisplayName("steps are held in time order however they were listed")
    void stepsAreSorted() {
        Scenario scenario = new Scenario("t", "test", Duration.ofSeconds(60), List.of(
                new Scenario.Step(Duration.ofSeconds(30), Scenario.Kind.NOMINAL, 0, Set.of(), "last"),
                new Scenario.Step(Duration.ofSeconds(10), Scenario.Kind.VOLTAGE, 264.0, Set.of(), "first")));

        assertEquals("first", scenario.getSteps().get(0).description());
        assertEquals("last", scenario.getSteps().get(1).description());
    }

    @Test
    @DisplayName("a step after the scenario ends is refused")
    void refusesAStepOutsideTheDuration() {
        assertThrows(IllegalArgumentException.class, () -> new Scenario("t", "test",
                Duration.ofSeconds(10), List.of(new Scenario.Step(Duration.ofSeconds(11),
                        Scenario.Kind.NOMINAL, 0, Set.of(), "too late"))));
    }

    @Test
    @DisplayName("an empty scenario is refused")
    void refusesAnEmptyScenario() {
        assertThrows(IllegalArgumentException.class,
                () -> new Scenario("t", "test", Duration.ofSeconds(10), List.of()));
    }

    @Test
    @DisplayName("an unknown scenario name names the ones that exist")
    void unknownNameIsActionable() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Scenario.byName("no-such-scenario"));
        assertTrue(failure.getMessage().contains("incident"),
                "the message should list what is available, was: " + failure.getMessage());
    }

    @Test
    @DisplayName("the incident scenario aims every fault past the limit it is meant to trip")
    void incidentFaultsCrossTheSeededThresholds() {
        Scenario incident = Scenario.incident();

        boolean sawSpike = false;
        boolean sawSag = false;
        boolean sawOverload = false;

        for (Scenario.Step step : incident.getSteps()) {
            switch (step.kind()) {
                case VOLTAGE -> {
                    if (step.value() > SPIKE_CEILING) {
                        sawSpike = true;
                    } else if (step.value() < SAG_FLOOR) {
                        sawSag = true;
                    } else {
                        throw new AssertionError("scripted voltage " + step.value() + " V sits "
                                + "inside the supply band, so '" + step.description()
                                + "' would raise no alert at all");
                    }
                }
                case POWER -> {
                    assertTrue(step.involves(1), "the only scripted overload is the refrigerator's");
                    assertTrue(step.value() > FRIDGE_CEILING,
                            "scripted load " + step.value() + " W is under the 500 W ceiling");
                    sawOverload = true;
                }
                case NOMINAL, CORRUPT, NOTE -> { /* nothing to check */ }
            }
        }

        assertTrue(sawSpike, "the incident should contain a voltage spike");
        assertTrue(sawSag, "the incident should contain a voltage sag");
        assertTrue(sawOverload, "the incident should contain a load overload");
    }

    @Test
    @DisplayName("the incident's threshold-edit cue leaves the overload running")
    void incidentEditCueDoesNotEndTheOverload() {
        Scenario incident = Scenario.incident();

        Scenario.Step cue = incident.getSteps().stream()
                .filter(step -> step.kind() == Scenario.Kind.NOTE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the incident should cue the threshold edit"));

        // A second after the operator is told to edit the threshold, the refrigerator must
        // still be drawing too much — otherwise the alerts stop whether or not they edit it.
        Scenario.Step stillInForce = incident.sustainedStepFor(1, cue.at().plusSeconds(1));
        assertNotNull(stillInForce, "the overload should outlive the cue");
        assertEquals(Scenario.Kind.POWER, stillInForce.kind());
    }

    @Test
    @DisplayName("the incident ends with everything nominal")
    void incidentEndsClean() {
        Scenario incident = Scenario.incident();
        Duration end = incident.getDuration();

        for (int deviceId = 1; deviceId <= 6; deviceId++) {
            assertNull(incident.sustainedStepFor(deviceId, end),
                    "device " + deviceId + " should be back to nominal when the scenario ends");
        }
    }

    @Test
    @DisplayName("the running order is printable and covers every step")
    void timelineIsPrintable() {
        Scenario incident = Scenario.incident();
        List<String> lines = incident.describeTimeline();

        assertEquals(incident.getSteps().size(), lines.size());
        assertFalse(lines.get(0).isBlank());
    }
}
