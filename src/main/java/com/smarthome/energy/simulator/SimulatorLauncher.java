package com.smarthome.energy.simulator;

import com.smarthome.energy.server.ServerConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Entry point that starts a fleet of {@link MeterSimulator}s, one per configured appliance.
 *
 * <p>Reads the set of appliance profiles (matching the seeded {@code devices} rows), then
 * launches each simulator on its own thread so they stream concurrently. This is the
 * client-side load generator used to exercise the server end to end without real hardware.</p>
 *
 * <p>Defaults — the server's host and port, the tick interval, and the anomaly rate — come
 * from the same {@code db.properties} the server reads, through {@link ServerConfig}, so a
 * single copied configuration file brings up a matching pair. Every one of them can be
 * overridden on the command line for a specific experiment.</p>
 *
 * <p>The random seed is reported at start-up and settable with {@code --seed}: a run that
 * produced something interesting can be replayed exactly, which is the difference between a
 * demo and an anecdote.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals, threading.</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public final class SimulatorLauncher {

    private SimulatorLauncher() {
        // Entry point only.
    }

    /**
     * Starts one meter per profile and streams until interrupted.
     *
     * @param args {@code --host H}, {@code --port N}, {@code --interval MS},
     *             {@code --anomaly P}, {@code --corrupt P}, {@code --seed N},
     *             {@code --devices 1,2,3}, {@code --help}
     */
    public static void main(String[] args) {
        ServerConfig config;
        String host = "localhost";
        double corruptionProbability = 0.0;
        long seed = System.currentTimeMillis();
        Set<Integer> selectedDevices = new LinkedHashSet<>();
        Scenario scenario = null;

        try {
            config = ServerConfig.load();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = stringArg(args, ++i);
                    case "--port" -> config = config.withMeterPort(intArg(args, ++i));
                    case "--interval" -> config = config.withSimulatorIntervalMillis(longArg(args, ++i));
                    case "--anomaly" -> config = config.withAnomalyProbability(doubleArg(args, ++i));
                    case "--corrupt" -> corruptionProbability = doubleArg(args, ++i);
                    case "--seed" -> seed = longArg(args, ++i);
                    case "--devices" -> selectedDevices.addAll(parseDeviceIds(stringArg(args, ++i)));
                    case "--scenario" -> scenario = Scenario.byName(stringArg(args, ++i));
                    case "--list-scenarios" -> {
                        printScenarios();
                        return;
                    }
                    case "--help", "-h" -> {
                        printUsage();
                        return;
                    }
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }
            if (scenario != null) {
                // A replay whose faults competed with random ones would be neither scripted nor
                // random, and the running order printed below would stop being what happens.
                config = config.withAnomalyProbability(0.0);
            }
            if (corruptionProbability < 0.0 || corruptionProbability > 1.0) {
                throw new IllegalArgumentException("--corrupt must be within [0,1], was "
                        + corruptionProbability);
            }
        } catch (IllegalArgumentException e) {
            System.err.println("[simulator] " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        List<ApplianceProfile> fleet = selectFleet(selectedDevices);
        if (fleet.isEmpty()) {
            System.err.println("[simulator] no profiles selected; --devices must name ids from "
                    + ApplianceProfile.defaultFleet().stream().map(ApplianceProfile::getDeviceId).toList());
            System.exit(2);
            return;
        }

        System.out.println("[simulator] streaming " + fleet.size() + " meter(s) to " + host + ":"
                + config.getMeterPort() + " every " + config.getSimulatorIntervalMillis() + "ms"
                + ", anomaly p=" + config.getAnomalyProbability()
                + ", corruption p=" + corruptionProbability
                + ", seed=" + seed);

        // One start instant for the whole fleet: the scenario's offsets are from the moment the
        // replay began, and six meters each timing from their own start would drift apart by
        // however long the fleet took to connect.
        Instant scenarioStart = scenario == null ? null : Instant.now();
        if (scenario != null) {
            printRunningOrder(scenario);
        }

        List<MeterSimulator> meters = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (ApplianceProfile profile : fleet) {
            // Each meter gets its own generator and its own seed offset: sharing one Random
            // across threads would make the runs neither reproducible nor thread-safe.
            long meterSeed = seed + profile.getDeviceId();
            WaveformGenerator generator = new WaveformGenerator(config.getAnomalyProbability(), meterSeed);
            MeterSimulator meter = new MeterSimulator(profile, host, config.getMeterPort(),
                    config.getSimulatorIntervalMillis(), generator, corruptionProbability, meterSeed,
                    scenario, scenarioStart);
            meters.add(meter);

            Thread thread = new Thread(meter, "meter-" + profile.getDeviceId());
            threads.add(thread);
            thread.start();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[simulator] stopping " + meters.size() + " meter(s)");
            meters.forEach(MeterSimulator::stop);
        }, "simulator-shutdown"));

        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                meters.forEach(MeterSimulator::stop);
                return;
            }
        }

        long sent = meters.stream().mapToLong(MeterSimulator::getSentCount).sum();
        System.out.println("[simulator] all meters stopped; " + sent + " reading(s) sent in total");
    }

    /** Prints what is about to happen, so an audience knows what to watch for. */
    private static void printRunningOrder(Scenario scenario) {
        System.out.println("[simulator] replaying scenario '" + scenario.getName() + "' — "
                + scenario.getSummary());
        System.out.println("[simulator] the meters stop after "
                + scenario.getDuration().getSeconds() + "s. Running order:");
        for (String line : scenario.describeTimeline()) {
            System.out.println("[simulator]   " + line);
        }
        System.out.println("[simulator] random anomalies are off for the duration.");
    }

    /** Lists the scenarios {@code --scenario} accepts. */
    private static void printScenarios() {
        System.out.println("Available scenarios:");
        for (Scenario scenario : Scenario.all()) {
            System.out.println("  " + scenario.getName() + " (" + scenario.getDuration().getSeconds()
                    + "s) — " + scenario.getSummary());
            for (String line : scenario.describeTimeline()) {
                System.out.println("      " + line);
            }
        }
    }

    /** Returns the requested profiles, or the whole fleet when none were named. */
    private static List<ApplianceProfile> selectFleet(Set<Integer> deviceIds) {
        List<ApplianceProfile> fleet = ApplianceProfile.defaultFleet();
        if (deviceIds.isEmpty()) {
            return fleet;
        }
        return fleet.stream().filter(profile -> deviceIds.contains(profile.getDeviceId())).toList();
    }

    private static Set<Integer> parseDeviceIds(String raw) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Integer.valueOf(trimmed));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--devices takes a comma-separated list of ids, "
                        + "got '" + raw + "'", e);
            }
        }
        return ids;
    }

    private static String stringArg(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException("option " + args[index - 1] + " needs a value");
        }
        return args[index];
    }

    private static int intArg(String[] args, int index) {
        try {
            return Integer.parseInt(stringArg(args, index));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("option " + args[index - 1] + " needs a whole number, "
                    + "got '" + args[index] + "'", e);
        }
    }

    private static long longArg(String[] args, int index) {
        try {
            return Long.parseLong(stringArg(args, index));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("option " + args[index - 1] + " needs a whole number, "
                    + "got '" + args[index] + "'", e);
        }
    }

    private static double doubleArg(String[] args, int index) {
        try {
            return Double.parseDouble(stringArg(args, index));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("option " + args[index - 1] + " needs a number, "
                    + "got '" + args[index] + "'", e);
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage: SimulatorLauncher [options]

                  --host H          server host (default localhost)
                  --port N          server meter port (default from db.properties)
                  --interval MS     milliseconds between readings (default from db.properties)
                  --anomaly P       probability [0,1] that a reading is an injected anomaly
                  --corrupt P       probability [0,1] that a frame is damaged before sending,
                                    to exercise the server's DFA rejection path (default 0)
                  --devices 1,2,3   run only these device ids (default: the whole seeded fleet)
                  --seed N          seed the generators so the run can be replayed exactly
                  --scenario NAME   replay a scripted fault timeline instead of relying on
                                    random anomalies, then stop; turns --anomaly off
                  --list-scenarios  print the available scenarios and their running orders
                  --help            show this message
                """);
    }
}
