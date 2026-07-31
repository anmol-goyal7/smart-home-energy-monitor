package com.smarthome.energy.simulator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A scripted timeline of faults, so a demonstration does not depend on a random anomaly
 * firing at the right moment.
 *
 * <p>{@link WaveformGenerator} injects anomalies with a probability, which is the right model
 * for a system left running and the wrong one for a live demonstration: an evaluator watching
 * a dashboard for three minutes should see a voltage spike because the script says so at
 * 1:00, not because a coin came up heads. A scenario replaces the dice with a schedule.</p>
 *
 * <p>A scenario is a list of {@link Step}s, each at a fixed offset from the moment the
 * simulators start. Steps are of two kinds:</p>
 *
 * <ul>
 *   <li><strong>Sustained</strong> ({@link Kind#VOLTAGE}, {@link Kind#POWER},
 *       {@link Kind#NOMINAL}) — they change what the affected meters report from that moment
 *       until another step supersedes them. A fault that lasted exactly one reading would be
 *       gone from the screen before anyone could point at it.</li>
 *   <li><strong>One-shot</strong> ({@link Kind#CORRUPT}) — a single damaged frame, fired once.
 *       Sustaining that one would fill the log with rejections and demonstrate nothing beyond
 *       the first.</li>
 * </ul>
 *
 * <h2>Why the whole timeline is data rather than code</h2>
 *
 * <p>Holding the script as a list of steps means the same object can be replayed by the
 * simulators, printed as a running order before the demonstration starts, and read in a viva
 * as the answer to "what exactly are we about to see". A scenario expressed as {@code sleep}
 * calls scattered through a shell script can do only the first.</p>
 *
 * <p>Instances are immutable and shared across every meter thread; the one piece of per-meter
 * state a replay needs — which one-shot steps have already fired — lives in
 * {@link MeterSimulator}, not here.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (immutable value objects, composition).</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public final class Scenario {

    /** What a step does to the meters it names. */
    public enum Kind {
        /** Return the named meters to their ordinary generated waveform. */
        NOMINAL,
        /** Hold the named meters' supply voltage at the step's value, in volts. */
        VOLTAGE,
        /** Hold the named meters' load at the step's value, in watts. */
        POWER,
        /** Damage exactly one frame from each named meter, once. */
        CORRUPT,
        /**
         * Change nothing; print a cue at this moment in the running order.
         *
         * <p>The scenario's climax is an operator editing a threshold and the alerts stopping.
         * That moment has to be a cue and not a state change, because a step that quietly
         * dropped the load back to normal at the same instant would produce exactly the same
         * screen whether the operator touched the editor or not — and the one thing the
         * demonstration is supposed to prove is that the edit is what did it.</p>
         */
        NOTE
    }

    /**
     * One entry in the running order.
     *
     * @param at          offset from the start of the replay
     * @param kind        what the step does
     * @param value       volts for {@link Kind#VOLTAGE}, watts for {@link Kind#POWER}, ignored
     *                    otherwise
     * @param devices     the device ids affected; empty means every meter
     * @param description what to point at while it happens, printed in the running order
     */
    public record Step(Duration at, Kind kind, double value, Set<Integer> devices,
                       String description) {

        /** Canonical constructor, defensively copying the device set. */
        public Step {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(description, "description");
            devices = Set.copyOf(Objects.requireNonNull(devices, "devices"));
            if (at.isNegative()) {
                throw new IllegalArgumentException("a step cannot happen before the replay starts");
            }
        }

        /**
         * @param deviceId the meter to test
         * @return true if this step applies to that meter
         */
        public boolean involves(int deviceId) {
            return devices.isEmpty() || devices.contains(deviceId);
        }

        /** @return true if this step fires once rather than lasting until superseded. */
        public boolean isOneShot() {
            return kind == Kind.CORRUPT;
        }

        /** @return the step as it appears in the printed running order. */
        public String describe() {
            long seconds = at.getSeconds();
            String scope = devices.isEmpty() ? "all meters"
                    : "device" + (devices.size() == 1 ? " " : "s ") + devices;
            return String.format(Locale.ROOT, "%d:%02d  %-11s %-28s %s",
                    seconds / 60, seconds % 60, kind, scope, description);
        }
    }

    private final String name;
    private final String summary;
    private final Duration duration;
    private final List<Step> steps;

    /**
     * @param name     the name {@code --scenario} selects this by; must not be null
     * @param summary  one line describing what the scenario demonstrates; must not be null
     * @param duration how long the replay runs before the simulators stop; must be positive
     * @param steps    the running order; must not be null or empty
     * @throws IllegalArgumentException if the duration is not positive, the steps are empty,
     *                                  or a step falls outside the duration
     * @throws NullPointerException     if any argument is null
     */
    public Scenario(String name, String summary, Duration duration, List<Step> steps) {
        this.name = Objects.requireNonNull(name, "name");
        this.summary = Objects.requireNonNull(summary, "summary");
        this.duration = Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(steps, "steps");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("a scenario must last a positive time, was " + duration);
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a scenario with no steps would replay nothing");
        }
        for (Step step : steps) {
            if (step.at().compareTo(duration) > 0) {
                throw new IllegalArgumentException("step '" + step.description() + "' happens at "
                        + step.at() + ", after the scenario ends at " + duration);
            }
        }
        // Sorted once here so that resolving the state at an instant is a scan in time order
        // rather than a search, and so the printed running order is in the order it happens.
        List<Step> ordered = new ArrayList<>(steps);
        ordered.sort((a, b) -> a.at().compareTo(b.at()));
        this.steps = List.copyOf(ordered);
    }

    /**
     * The scenario the README documents: three minutes, one fault of each kind, ending back at
     * nominal.
     *
     * <p>The values are chosen against the seeded thresholds rather than picked to look
     * dramatic: 264 V is above the 253 V ceiling, 196 V below the 207 V floor, and 540 W above
     * the refrigerator's own 500 W ceiling but under the 5% margin that would make it critical
     * — so the overload arrives as a WARNING and the voltage faults as CRITICAL, which is the
     * severity split the rules exist to make.</p>
     *
     * @return the {@code incident} scenario
     */
    public static Scenario incident() {
        return new Scenario("incident",
                "a supply fault, a malformed frame, and an overload, in three minutes",
                Duration.ofSeconds(180),
                List.of(
                        new Step(Duration.ofSeconds(45), Kind.CORRUPT, 0, Set.of(3),
                                "one damaged frame — the automaton hits its trap state and the "
                                        + "server's rejection names the column"),
                        new Step(Duration.ofSeconds(60), Kind.VOLTAGE, 264.0, Set.of(2),
                                "HVAC supply climbs past the 253 V ceiling — VOLTAGE_SPIKE, CRITICAL"),
                        new Step(Duration.ofSeconds(80), Kind.NOMINAL, 0, Set.of(2),
                                "HVAC supply recovers; its tile goes green again"),
                        new Step(Duration.ofSeconds(90), Kind.VOLTAGE, 196.0, Set.of(),
                                "whole-house sag below the 207 V floor — simultaneous VOLTAGE_SAG "
                                        + "on every device"),
                        new Step(Duration.ofSeconds(110), Kind.NOMINAL, 0, Set.of(),
                                "supply recovers across the house"),
                        new Step(Duration.ofSeconds(120), Kind.POWER, 540.0, Set.of(1),
                                "refrigerator draws 540 W against its 500 W ceiling — "
                                        + "LOAD_OVERLOAD, WARNING, once per reading"),
                        new Step(Duration.ofSeconds(140), Kind.NOTE, 0, Set.of(1),
                                "now raise device 1's POWER maximum to 560 in the Thresholds tab "
                                        + "and commit — the load does NOT change, so if the alerts "
                                        + "stop it is the reload that stopped them"),
                        new Step(Duration.ofSeconds(160), Kind.NOMINAL, 0, Set.of(),
                                "everything back to nominal; the event log stops growing")));
    }

    /**
     * Looks a scenario up by name.
     *
     * @param name the name given to {@code --scenario}; must not be null
     * @return the scenario
     * @throws IllegalArgumentException if no scenario has that name
     * @throws NullPointerException     if {@code name} is null
     */
    public static Scenario byName(String name) {
        Objects.requireNonNull(name, "name");
        for (Scenario scenario : all()) {
            if (scenario.getName().equalsIgnoreCase(name.trim())) {
                return scenario;
            }
        }
        throw new IllegalArgumentException("unknown scenario '" + name + "'; available: "
                + all().stream().map(Scenario::getName).toList());
    }

    /** @return every scenario the simulators can replay. */
    public static List<Scenario> all() {
        return List.of(incident());
    }

    /** @return the name {@code --scenario} selects this by. */
    public String getName() {
        return name;
    }

    /** @return one line describing what this scenario demonstrates. */
    public String getSummary() {
        return summary;
    }

    /** @return how long the replay runs before the simulators stop. */
    public Duration getDuration() {
        return duration;
    }

    /** @return the running order, in time order. */
    public List<Step> getSteps() {
        return steps;
    }

    /**
     * The sustained step in force for one meter at one point in the replay.
     *
     * <p>Scans forward for the last non-one-shot step that has already happened and names this
     * device. Deriving the state from the timeline on every tick, rather than tracking it as
     * mutable per-meter state, means a meter that was busy or restarted lands in the right
     * place instead of in whatever state it left off in.</p>
     *
     * @param deviceId the meter to resolve
     * @param elapsed  how long the replay has been running; must not be null
     * @return the step in force, or null if this meter is running its ordinary waveform
     * @throws NullPointerException if {@code elapsed} is null
     */
    public Step sustainedStepFor(int deviceId, Duration elapsed) {
        Objects.requireNonNull(elapsed, "elapsed");
        Step active = null;
        for (Step step : steps) {
            if (step.at().compareTo(elapsed) > 0) {
                break;
            }
            // NOTE steps are cues for the audience, not changes to the waveform, so they
            // neither apply nor clear.
            if (!step.isOneShot() && step.kind() != Kind.NOTE && step.involves(deviceId)) {
                active = step.kind() == Kind.NOMINAL ? null : step;
            }
        }
        return active;
    }

    /**
     * The one-shot steps that have come due for one meter.
     *
     * @param deviceId the meter to resolve
     * @param elapsed  how long the replay has been running; must not be null
     * @return the indices, into {@link #getSteps()}, of the one-shot steps due for this meter
     * @throws NullPointerException if {@code elapsed} is null
     */
    public List<Integer> dueOneShots(int deviceId, Duration elapsed) {
        Objects.requireNonNull(elapsed, "elapsed");
        List<Integer> due = new ArrayList<>(1);
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (step.at().compareTo(elapsed) > 0) {
                break;
            }
            if (step.isOneShot() && step.involves(deviceId)) {
                due.add(i);
            }
        }
        return due;
    }

    /** @return the running order as printable lines, for the demo script's banner. */
    public List<String> describeTimeline() {
        Set<String> lines = new LinkedHashSet<>();
        for (Step step : steps) {
            lines.add(step.describe());
        }
        return List.copyOf(lines);
    }

    @Override
    public String toString() {
        return "Scenario[" + name + ", " + duration.getSeconds() + "s, " + steps.size() + " steps]";
    }
}
