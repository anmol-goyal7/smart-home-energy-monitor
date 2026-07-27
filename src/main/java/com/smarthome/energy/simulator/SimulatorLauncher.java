package com.smarthome.energy.simulator;

/**
 * Entry point that starts a fleet of {@link MeterSimulator}s, one per configured appliance.
 *
 * <p>Reads the set of appliance profiles (matching the seeded {@code devices} rows), then
 * launches each simulator on its own thread so they stream concurrently. This is the
 * client-side load generator used to exercise the server end to end without real hardware.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals, threading.</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public final class SimulatorLauncher {
    // Placeholder — main() and fleet start-up implemented by the author.
}
