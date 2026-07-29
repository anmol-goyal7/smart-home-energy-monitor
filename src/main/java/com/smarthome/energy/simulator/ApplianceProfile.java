package com.smarthome.energy.simulator;

import java.util.List;
import java.util.Objects;

/**
 * Describes the electrical behaviour of one simulated appliance.
 *
 * <p>Holds the device id, nominal voltage, and a typical power band (idle and running
 * watts) that the {@link WaveformGenerator} samples from to produce plausible readings.
 * Different profiles — a refrigerator that cycles, an HVAC unit with a heavy start-up
 * surge, an always-on router — give the demo a realistic mix of load patterns.</p>
 *
 * <h2>The fleet mirrors the seed data</h2>
 *
 * <p>{@link #defaultFleet()} is the six appliances in {@code sql/seed.sql}, with the same
 * ids, names, and ratings. That is not decoration: the server refuses readings for a device
 * id that is not in the {@code devices} table, so a profile whose id has drifted away from
 * the seed produces a simulator that connects, streams, and is silently ignored.</p>
 *
 * <p>Every profile's running band, including its start-up surge, is deliberately kept under
 * the overload ceiling seeded for that device in {@code thresholds}. Normal operation must
 * not raise alerts — a demo where the event log fills up on its own teaches an evaluator
 * nothing about detection. Anomalies are injected on purpose by {@link WaveformGenerator},
 * and {@link #getOverloadWatts()} records the ceiling so an injected overload can be aimed
 * just past it rather than guessed at.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals.</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public final class ApplianceProfile {

    private final int deviceId;
    private final String name;
    private final double nominalVoltage;
    private final double idleWatts;
    private final double runningWatts;
    private final double overloadWatts;
    private final double dutyCycle;
    private final int cycleSeconds;
    private final double startupSurge;

    /**
     * Creates a profile.
     *
     * @param deviceId       id of the seeded device this meter reports as; must be positive
     * @param name           human label, matching the seeded device name; must not be null
     * @param nominalVoltage supply voltage the meter jitters around, in volts
     * @param idleWatts      draw while the appliance is off or standing by
     * @param runningWatts   draw while the appliance is working
     * @param overloadWatts  the seeded overload ceiling for this device, in watts
     * @param dutyCycle      fraction of each cycle spent running, within {@code [0,1]}
     * @param cycleSeconds   length of one on/off cycle in seconds; must be positive
     * @param startupSurge   multiplier applied to {@code runningWatts} just after switch-on;
     *                       must be at least 1
     * @throws IllegalArgumentException if a numeric argument is outside its range
     * @throws NullPointerException     if {@code name} is null
     */
    public ApplianceProfile(int deviceId, String name, double nominalVoltage, double idleWatts,
                            double runningWatts, double overloadWatts, double dutyCycle,
                            int cycleSeconds, double startupSurge) {
        if (deviceId <= 0) {
            throw new IllegalArgumentException("deviceId must be positive, was " + deviceId);
        }
        if (dutyCycle < 0.0 || dutyCycle > 1.0) {
            throw new IllegalArgumentException("dutyCycle must be within [0,1], was " + dutyCycle);
        }
        if (cycleSeconds <= 0) {
            throw new IllegalArgumentException("cycleSeconds must be positive, was " + cycleSeconds);
        }
        if (startupSurge < 1.0) {
            throw new IllegalArgumentException("startupSurge must be at least 1, was " + startupSurge);
        }
        this.deviceId = deviceId;
        this.name = Objects.requireNonNull(name, "name");
        this.nominalVoltage = nominalVoltage;
        this.idleWatts = idleWatts;
        this.runningWatts = runningWatts;
        this.overloadWatts = overloadWatts;
        this.dutyCycle = dutyCycle;
        this.cycleSeconds = cycleSeconds;
        this.startupSurge = startupSurge;
    }

    /**
     * The six appliances seeded by {@code sql/seed.sql}, in device-id order.
     *
     * @return the default fleet; never empty
     */
    public static List<ApplianceProfile> defaultFleet() {
        return List.of(
                //                 id  name                     V     idle  run    ceiling duty  cycle surge
                new ApplianceProfile(1, "Kitchen Refrigerator", 230, 12,    190,    500,   0.45, 120,  1.60),
                new ApplianceProfile(2, "Living Room HVAC",     230, 40,   1750,   2600,   0.60, 300,  1.35),
                new ApplianceProfile(3, "Washing Machine",      230,  2,   1900,   2600,   0.25, 600,  1.20),
                new ApplianceProfile(4, "Water Heater",         230,  5,   2800,   3300,   0.30, 480,  1.05),
                new ApplianceProfile(5, "Home Office Desktop",  230, 60,    320,    600,   0.70, 240,  1.00),
                new ApplianceProfile(6, "Network Router",       230,  9,     11,     40,   1.00,  60,  1.00));
    }

    /** @return id of the seeded device this meter reports as. */
    public int getDeviceId() {
        return deviceId;
    }

    /** @return the human label, matching the seeded device name. */
    public String getName() {
        return name;
    }

    /** @return the supply voltage readings jitter around, in volts. */
    public double getNominalVoltage() {
        return nominalVoltage;
    }

    /** @return draw while the appliance is off or standing by, in watts. */
    public double getIdleWatts() {
        return idleWatts;
    }

    /** @return draw while the appliance is working, in watts. */
    public double getRunningWatts() {
        return runningWatts;
    }

    /** @return the seeded overload ceiling for this device, in watts. */
    public double getOverloadWatts() {
        return overloadWatts;
    }

    /** @return the fraction of each cycle spent running. */
    public double getDutyCycle() {
        return dutyCycle;
    }

    /** @return the length of one on/off cycle, in seconds. */
    public int getCycleSeconds() {
        return cycleSeconds;
    }

    /** @return the multiplier applied to {@link #getRunningWatts()} just after switch-on. */
    public double getStartupSurge() {
        return startupSurge;
    }

    @Override
    public String toString() {
        return "ApplianceProfile[" + deviceId + " " + name + ", " + idleWatts + "-" + runningWatts
                + "W, ceiling " + overloadWatts + "W, duty " + dutyCycle + " of " + cycleSeconds + "s]";
    }
}
