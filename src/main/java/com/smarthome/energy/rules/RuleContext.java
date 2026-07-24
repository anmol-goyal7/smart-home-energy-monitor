package com.smarthome.energy.rules;

/**
 * Read-only snapshot of the reference data a {@link DetectionRule} needs to make a
 * decision: the thresholds in force and the metadata of the device being evaluated.
 *
 * <p>Passing a context object (rather than querying the database inside each rule) keeps
 * rules pure and fast — they operate only on values already in memory. The
 * {@link RuleEngine} builds and refreshes the context from {@code ThresholdDao} and
 * {@code DeviceDao}.</p>
 *
 * <p>Intended API:
 * <ul>
 *   <li>{@code Threshold thresholdFor(int deviceId, Metric metric)} — device override or default.</li>
 *   <li>{@code Device device(int deviceId)} — rated values for the device.</li>
 * </ul>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals.</p>
 *
 * @author Team member 3 (meter simulators & rule engine)
 */
public final class RuleContext {
    // Placeholder — threshold/device lookup implemented by the author.
}
