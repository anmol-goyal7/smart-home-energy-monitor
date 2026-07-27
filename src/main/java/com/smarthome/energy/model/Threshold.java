package com.smarthome.energy.model;

import java.util.Objects;

/**
 * Value object describing a detection threshold for one metric on one device.
 *
 * <p>Mirrors a row of the {@code thresholds} table. A threshold carries an optional
 * lower bound and an optional upper bound for a {@link Metric}. The rule engine reads
 * these bounds to decide whether a {@link Reading} is anomalous. A {@code null}
 * {@code deviceId} denotes a default that applies to every device that lacks a specific
 * override.</p>
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code deviceId} — target device, or {@code null} for the global default.</li>
 *   <li>{@code metric} — which quantity this threshold constrains ({@link Metric}).</li>
 *   <li>{@code minValue} — lower bound; a reading below it is a sag/under-condition.</li>
 *   <li>{@code maxValue} — upper bound; a reading above it is a spike/overload.</li>
 *   <li>{@code description} — optional human note, carried through from the seed data.</li>
 * </ul>
 *
 * <p>{@code deviceId}, {@code minValue}, and {@code maxValue} are boxed because all three
 * are genuinely nullable in the schema: a global default has no device, and a row may bound
 * only one side of a metric (the seeded {@code POWER} rows set a ceiling and no floor).
 * Rather than leave every rule to null-check them, the comparisons live here in
 * {@link #isBelowMin} and {@link #isAboveMax}, which report {@code false} for an absent
 * bound — an unbounded side cannot be violated.</p>
 *
 * <p>There is no {@code thresholdId} field. The table has a unique key on
 * {@code (device_id, metric)}, so that pair identifies a row on its own, and the dashboard's
 * threshold editor updates against it rather than against a surrogate key it would first
 * have to fetch.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals; Unit III — JDBC entity.</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public final class Threshold {

    private final Integer deviceId;
    private final Metric metric;
    private final Double minValue;
    private final Double maxValue;
    private final String description;

    /**
     * Creates a threshold.
     *
     * @param deviceId    target device, or {@code null} for the global default
     * @param metric      the quantity this threshold bounds; must not be null
     * @param minValue    lower bound, or {@code null} if this metric is unbounded below
     * @param maxValue    upper bound, or {@code null} if this metric is unbounded above
     * @param description optional human note; may be null
     * @throws NullPointerException     if {@code metric} is null
     * @throws IllegalArgumentException if both bounds are null, or if min exceeds max
     */
    public Threshold(Integer deviceId, Metric metric, Double minValue, Double maxValue, String description) {
        this.metric = Objects.requireNonNull(metric, "metric");
        if (minValue == null && maxValue == null) {
            throw new IllegalArgumentException(
                    "threshold for " + metric + " bounds neither side; at least one of min/max is required");
        }
        if (minValue != null && maxValue != null && minValue > maxValue) {
            throw new IllegalArgumentException(
                    "threshold for " + metric + " has min " + minValue + " above max " + maxValue);
        }
        this.deviceId = deviceId;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.description = description;
    }

    /** @return the target device id, or {@code null} if this is the global default. */
    public Integer getDeviceId() {
        return deviceId;
    }

    /** @return the quantity this threshold bounds. */
    public Metric getMetric() {
        return metric;
    }

    /** @return the lower bound, or {@code null} if unbounded below. */
    public Double getMinValue() {
        return minValue;
    }

    /** @return the upper bound, or {@code null} if unbounded above. */
    public Double getMaxValue() {
        return maxValue;
    }

    /** @return the optional human note, or {@code null}. */
    public String getDescription() {
        return description;
    }

    /** @return true if this row is the global default rather than a device override. */
    public boolean isGlobalDefault() {
        return deviceId == null;
    }

    /** @return true if a lower bound is set. */
    public boolean hasMin() {
        return minValue != null;
    }

    /** @return true if an upper bound is set. */
    public boolean hasMax() {
        return maxValue != null;
    }

    /**
     * @param value a measured value of this threshold's metric
     * @return true if the value falls below the lower bound; false if there is no lower bound
     */
    public boolean isBelowMin(double value) {
        return minValue != null && value < minValue;
    }

    /**
     * @param value a measured value of this threshold's metric
     * @return true if the value rises above the upper bound; false if there is no upper bound
     */
    public boolean isAboveMax(double value) {
        return maxValue != null && value > maxValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Threshold other)) {
            return false;
        }
        return Objects.equals(deviceId, other.deviceId)
                && metric == other.metric
                && Objects.equals(minValue, other.minValue)
                && Objects.equals(maxValue, other.maxValue)
                && Objects.equals(description, other.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, metric, minValue, maxValue, description);
    }

    @Override
    public String toString() {
        return "Threshold[" + (isGlobalDefault() ? "default" : "device " + deviceId)
                + ", " + metric
                + ", min=" + minValue
                + ", max=" + maxValue + "]";
    }
}
