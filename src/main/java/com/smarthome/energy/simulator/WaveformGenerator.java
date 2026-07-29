package com.smarthome.energy.simulator;

import com.smarthome.energy.model.Reading;

import java.time.Instant;
import java.util.Objects;
import java.util.Random;

/**
 * Produces the numeric values a {@link MeterSimulator} emits each tick.
 *
 * <p>Given an {@link ApplianceProfile}, generates a voltage/current/power sample that
 * jitters realistically around the appliance's nominal band, and, with a small
 * configurable probability, injects an anomaly (voltage spike, voltage sag, or power
 * overload) so the downstream rule engine has something to catch.</p>
 *
 * <h2>How the shape is produced</h2>
 *
 * <p>The on/off state is a function of the clock, not of a coin toss: the appliance is
 * running for the first {@code dutyCycle} fraction of every {@code cycleSeconds} window,
 * offset per device so the fleet does not switch on in unison. Deriving it from the
 * timestamp rather than from stored state means a restarted simulator picks the waveform up
 * where it would have been, and a chart of the history shows recognisable duty cycles
 * instead of noise. Power then gets Gaussian jitter, the first two seconds of an on-period
 * get the profile's start-up surge, and current is computed as {@code P/V} so the three
 * fields of a reading remain physically consistent with each other.</p>
 *
 * <p>Anomalies are aimed just past the seeded limits — a spike lands above the 253 V ceiling,
 * a sag below the 207 V floor, an overload above the device's own power ceiling — so an
 * injected anomaly reliably raises exactly the event it was meant to.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals.</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public final class WaveformGenerator {

    /** Standard deviation of the power jitter, as a fraction of the band value. */
    private static final double POWER_JITTER = 0.03;

    /** Standard deviation of the supply-voltage jitter, in volts. */
    private static final double VOLTAGE_JITTER = 1.5;

    /** Seconds after switch-on during which the start-up surge applies. */
    private static final double SURGE_SECONDS = 2.0;

    /** Voltage band an injected spike lands in, in volts (the seeded ceiling is 253). */
    private static final double SPIKE_MIN = 256.0;
    private static final double SPIKE_MAX = 268.0;

    /** Voltage band an injected sag lands in, in volts (the seeded floor is 207). */
    private static final double SAG_MIN = 190.0;
    private static final double SAG_MAX = 205.0;

    /** How far past its ceiling an injected overload pushes the load. */
    private static final double OVERLOAD_MIN_FACTOR = 1.05;
    private static final double OVERLOAD_MAX_FACTOR = 1.25;

    /** What was deliberately wrong with a generated sample, if anything. */
    public enum Anomaly {
        /** An ordinary reading. */
        NONE,
        /** Supply voltage pushed above the spike threshold. */
        VOLTAGE_SPIKE,
        /** Supply voltage pulled below the sag threshold. */
        VOLTAGE_SAG,
        /** Load pushed past the device's power ceiling. */
        LOAD_OVERLOAD
    }

    /**
     * One generated sample: the reading itself and what, if anything, was injected into it.
     *
     * @param reading the reading to send
     * @param anomaly what was injected, or {@link Anomaly#NONE}
     */
    public record Sample(Reading reading, Anomaly anomaly) {

        /** @return true if something was deliberately injected into this sample. */
        public boolean isAnomalous() {
            return anomaly != Anomaly.NONE;
        }
    }

    private final Random random;
    private final double anomalyProbability;

    /**
     * @param anomalyProbability probability in {@code [0,1]} that a sample carries an
     *                           injected anomaly
     * @param seed               seed for the jitter and anomaly draws, so a run can be
     *                           reproduced exactly
     * @throws IllegalArgumentException if the probability is outside {@code [0,1]}
     */
    public WaveformGenerator(double anomalyProbability, long seed) {
        if (anomalyProbability < 0.0 || anomalyProbability > 1.0) {
            throw new IllegalArgumentException("anomalyProbability must be within [0,1], was "
                    + anomalyProbability);
        }
        this.anomalyProbability = anomalyProbability;
        this.random = new Random(seed);
    }

    /**
     * Generates the sample this appliance would report at an instant.
     *
     * @param profile the appliance to model; must not be null
     * @param at      the measurement time; must not be null
     * @return the sample
     * @throws NullPointerException if either argument is null
     */
    public Sample next(ApplianceProfile profile, Instant at) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(at, "at");

        double voltage = profile.getNominalVoltage() + random.nextGaussian() * VOLTAGE_JITTER;
        double power = basePower(profile, at);
        Anomaly anomaly = drawAnomaly();

        switch (anomaly) {
            case VOLTAGE_SPIKE -> voltage = uniform(SPIKE_MIN, SPIKE_MAX);
            case VOLTAGE_SAG -> voltage = uniform(SAG_MIN, SAG_MAX);
            case LOAD_OVERLOAD -> power = profile.getOverloadWatts()
                    * uniform(OVERLOAD_MIN_FACTOR, OVERLOAD_MAX_FACTOR);
            case NONE -> { /* leave the sample as generated */ }
        }

        // Ohm's law keeps the three fields consistent: a chart of V x I should equal P.
        double current = power / voltage;
        return new Sample(Reading.fromEpochMillis(profile.getDeviceId(), at.toEpochMilli(),
                round(voltage), round(current), round(power)), anomaly);
    }

    /** The load the appliance is drawing at this instant, before any anomaly is applied. */
    private double basePower(ApplianceProfile profile, Instant at) {
        int cycleSeconds = profile.getCycleSeconds();
        // A per-device offset so six appliances do not all switch on at the same second.
        long offset = (profile.getDeviceId() * 37L) % cycleSeconds;
        double position = Math.floorMod(at.toEpochMilli() / 1000L + offset, cycleSeconds);

        double onSeconds = cycleSeconds * profile.getDutyCycle();
        boolean running = position < onSeconds;
        if (!running) {
            return jitter(profile.getIdleWatts());
        }

        double watts = jitter(profile.getRunningWatts());
        if (position < SURGE_SECONDS) {
            watts *= profile.getStartupSurge();
        }
        return watts;
    }

    /** Applies Gaussian jitter, never letting the result fall below zero. */
    private double jitter(double value) {
        return Math.max(0.0, value * (1.0 + random.nextGaussian() * POWER_JITTER));
    }

    private Anomaly drawAnomaly() {
        if (anomalyProbability <= 0.0 || random.nextDouble() >= anomalyProbability) {
            return Anomaly.NONE;
        }
        return switch (random.nextInt(3)) {
            case 0 -> Anomaly.VOLTAGE_SPIKE;
            case 1 -> Anomaly.VOLTAGE_SAG;
            default -> Anomaly.LOAD_OVERLOAD;
        };
    }

    private double uniform(double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    /**
     * Rounds to the two decimals the wire format and the {@code DECIMAL(_,2)} columns carry,
     * so the value the meter reports is the value that is stored — no silent re-rounding
     * between the two.
     */
    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
