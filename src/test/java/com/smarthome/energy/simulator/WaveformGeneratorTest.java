package com.smarthome.energy.simulator;

import com.smarthome.energy.model.Reading;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the generated waveform: that a seeded run is reproducible, that ordinary readings
 * stay inside the seeded thresholds, that injected anomalies land outside exactly the limit
 * they are aimed at, and that the three fields of a reading stay physically consistent.
 *
 * <p>The "ordinary readings raise no alerts" case is the one that matters for the demo: a
 * simulator whose normal operation trips the rule engine would fill the event log with noise
 * and make the deliberate anomalies impossible to point at.</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
class WaveformGeneratorTest {

    /** The seeded global supply band from {@code sql/seed.sql}. */
    private static final double SAG_FLOOR = 207.0;
    private static final double SPIKE_CEILING = 253.0;

    private static final Instant START = Instant.parse("2026-07-29T00:00:00Z");

    @Test
    @DisplayName("the same seed produces the same stream")
    void isReproducibleFromItsSeed() {
        ApplianceProfile profile = ApplianceProfile.defaultFleet().get(1);

        WaveformGenerator first = new WaveformGenerator(0.2, 4242L);
        WaveformGenerator second = new WaveformGenerator(0.2, 4242L);

        for (int i = 0; i < 500; i++) {
            Instant at = START.plusSeconds(i);
            WaveformGenerator.Sample a = first.next(profile, at);
            WaveformGenerator.Sample b = second.next(profile, at);
            assertEquals(a.reading(), b.reading(), "sample " + i + " differed");
            assertEquals(a.anomaly(), b.anomaly(), "anomaly " + i + " differed");
        }
    }

    @Test
    @DisplayName("with anomalies off, no appliance ever crosses its seeded limits")
    void ordinaryReadingsRaiseNoAlerts() {
        for (ApplianceProfile profile : ApplianceProfile.defaultFleet()) {
            WaveformGenerator generator = new WaveformGenerator(0.0, profile.getDeviceId());

            // A full day at one reading a second is far more than any demo, and covers every
            // phase of the duty cycle including the start-up surge.
            for (int second = 0; second < 86_400; second += 7) {
                Reading reading = generator.next(profile, START.plusSeconds(second)).reading();

                assertTrue(reading.getVoltage() > SAG_FLOOR,
                        profile.getName() + " sagged to " + reading.getVoltage() + " V");
                assertTrue(reading.getVoltage() < SPIKE_CEILING,
                        profile.getName() + " spiked to " + reading.getVoltage() + " V");
                assertTrue(reading.getPowerWatts() < profile.getOverloadWatts(),
                        profile.getName() + " drew " + reading.getPowerWatts()
                                + " W against a ceiling of " + profile.getOverloadWatts() + " W");
            }
        }
    }

    @Test
    @DisplayName("each injected anomaly lands past the limit it is aimed at")
    void injectedAnomaliesCrossTheirOwnLimit() {
        ApplianceProfile profile = ApplianceProfile.defaultFleet().get(0);
        WaveformGenerator generator = new WaveformGenerator(1.0, 99L);

        Map<WaveformGenerator.Anomaly, Integer> seen = new EnumMap<>(WaveformGenerator.Anomaly.class);
        for (int i = 0; i < 3_000; i++) {
            WaveformGenerator.Sample sample = generator.next(profile, START.plusSeconds(i));
            Reading reading = sample.reading();
            seen.merge(sample.anomaly(), 1, Integer::sum);

            switch (sample.anomaly()) {
                case VOLTAGE_SPIKE -> assertTrue(reading.getVoltage() > SPIKE_CEILING,
                        "a spike must exceed " + SPIKE_CEILING + " V, was " + reading.getVoltage());
                case VOLTAGE_SAG -> assertTrue(reading.getVoltage() < SAG_FLOOR,
                        "a sag must fall below " + SAG_FLOOR + " V, was " + reading.getVoltage());
                case LOAD_OVERLOAD -> assertTrue(reading.getPowerWatts() > profile.getOverloadWatts(),
                        "an overload must exceed " + profile.getOverloadWatts() + " W, was "
                                + reading.getPowerWatts());
                case NONE -> throw new AssertionError("probability 1.0 should inject every time");
            }
        }

        // All three kinds should turn up, or the demo can only ever show one of them.
        for (WaveformGenerator.Anomaly kind : List.of(WaveformGenerator.Anomaly.VOLTAGE_SPIKE,
                WaveformGenerator.Anomaly.VOLTAGE_SAG, WaveformGenerator.Anomaly.LOAD_OVERLOAD)) {
            assertTrue(seen.getOrDefault(kind, 0) > 100, kind + " was generated only "
                    + seen.getOrDefault(kind, 0) + " times");
        }
    }

    @Test
    @DisplayName("no anomalies are injected when the probability is zero")
    void injectsNothingAtZeroProbability() {
        ApplianceProfile profile = ApplianceProfile.defaultFleet().get(2);
        WaveformGenerator generator = new WaveformGenerator(0.0, 7L);

        for (int i = 0; i < 5_000; i++) {
            assertEquals(WaveformGenerator.Anomaly.NONE,
                    generator.next(profile, START.plusSeconds(i)).anomaly());
        }
    }

    @Test
    @DisplayName("power, voltage, and current stay consistent with each other")
    void keepsOhmsLaw() {
        ApplianceProfile profile = ApplianceProfile.defaultFleet().get(3);
        WaveformGenerator generator = new WaveformGenerator(0.1, 21L);

        for (int i = 0; i < 2_000; i++) {
            Reading reading = generator.next(profile, START.plusSeconds(i)).reading();
            double implied = reading.getVoltage() * reading.getCurrent();
            // Each field is rounded to two decimals independently, so the product is only
            // approximate; the tolerance is the rounding, not slack in the model.
            assertEquals(reading.getPowerWatts(), implied, 1.0 + reading.getPowerWatts() * 0.005,
                    "V x I should equal P for " + reading);
        }
    }

    @Test
    @DisplayName("the appliance cycles between its idle and running bands")
    void followsItsDutyCycle() {
        ApplianceProfile fridge = ApplianceProfile.defaultFleet().get(0);
        WaveformGenerator generator = new WaveformGenerator(0.0, 5L);

        double lowest = Double.MAX_VALUE;
        double highest = 0.0;
        for (int second = 0; second < fridge.getCycleSeconds() * 2; second++) {
            double watts = generator.next(fridge, START.plusSeconds(second)).reading().getPowerWatts();
            lowest = Math.min(lowest, watts);
            highest = Math.max(highest, watts);
        }

        assertTrue(lowest < fridge.getIdleWatts() * 1.5,
                "the appliance never idled; lowest draw was " + lowest + " W");
        assertTrue(highest > fridge.getRunningWatts() * 0.9,
                "the appliance never ran; highest draw was " + highest + " W");
    }

    @Test
    @DisplayName("the fleet matches the seeded devices")
    void fleetMatchesTheSeedData() {
        List<ApplianceProfile> fleet = ApplianceProfile.defaultFleet();
        assertEquals(6, fleet.size());
        for (int i = 0; i < fleet.size(); i++) {
            assertEquals(i + 1, fleet.get(i).getDeviceId(),
                    "profiles must line up with the seeded device ids");
        }
        assertEquals("Kitchen Refrigerator", fleet.get(0).getName());
        assertEquals(500.0, fleet.get(0).getOverloadWatts());
    }

    @Test
    @DisplayName("an out-of-range anomaly probability is refused")
    void rejectsAnImpossibleProbability() {
        assertThrows(IllegalArgumentException.class, () -> new WaveformGenerator(1.5, 1L));
        assertThrows(IllegalArgumentException.class, () -> new WaveformGenerator(-0.1, 1L));
    }

    @Test
    @DisplayName("the meter reports the instant it was asked about")
    void stampsTheRequestedInstant() {
        ApplianceProfile profile = ApplianceProfile.defaultFleet().get(4);
        Instant at = START.plus(Duration.ofMinutes(7)).plusMillis(123);
        Reading reading = new WaveformGenerator(0.0, 1L).next(profile, at).reading();

        assertEquals(at.toEpochMilli(), reading.getReadingEpochMillis());
        assertEquals(profile.getDeviceId(), reading.getDeviceId());
    }
}
