package com.smarthome.energy.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable value object representing a single power reading emitted by one smart
 * meter for one appliance at one instant.
 *
 * <p>A {@code Reading} is the atomic unit that flows through the whole system: it is
 * produced by a {@code MeterSimulator}, serialized onto the wire, validated by the
 * protocol layer, parsed back into this object on the server, persisted by
 * {@code ReadingDao}, evaluated by the {@code RuleEngine}, and finally rendered on the
 * dashboard.</p>
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code deviceId} — foreign key to the emitting device.</li>
 *   <li>{@code readingTimestamp} — the instant of the measurement at the meter.</li>
 *   <li>{@code voltage} — RMS volts.</li>
 *   <li>{@code current} — RMS amperes.</li>
 *   <li>{@code powerWatts} — real power in watts.</li>
 * </ul>
 *
 * <p>The wire format carries the timestamp as epoch milliseconds, but this object holds an
 * {@link Instant}. A bare {@code long} does not say which epoch or zone it counts from, and
 * the {@code readings.reading_ts} column is a {@code DATETIME(3)}, which carries no zone of
 * its own either. Converting once at each boundary — {@link #fromEpochMillis} on the way in
 * from the wire, an explicit UTC conversion in {@code ReadingDao} on the way to the database
 * — keeps that ambiguity out of the middle of the system.</p>
 *
 * <p>There is deliberately no {@code readingId} field. A reading arriving off the wire has
 * not been persisted and has no identity yet; the generated key is returned by
 * {@code ReadingDao.insert} to the one caller that needs it — the rule engine, linking an
 * event to the reading that triggered it — rather than being carried everywhere as a field
 * that is usually unset.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (encapsulation, immutability).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class Reading {

    private final int deviceId;
    private final Instant readingTimestamp;
    private final double voltage;
    private final double current;
    private final double powerWatts;

    /**
     * Creates a reading.
     *
     * @param deviceId         id of the device that produced the reading; must be positive
     * @param readingTimestamp instant of the measurement at the meter; must not be null
     * @param voltage          RMS voltage in volts
     * @param current          RMS current in amperes
     * @param powerWatts       real power in watts
     * @throws IllegalArgumentException if {@code deviceId} is not positive
     * @throws NullPointerException     if {@code readingTimestamp} is null
     */
    public Reading(int deviceId, Instant readingTimestamp, double voltage, double current, double powerWatts) {
        if (deviceId <= 0) {
            throw new IllegalArgumentException("deviceId must be positive, was " + deviceId);
        }
        this.deviceId = deviceId;
        this.readingTimestamp = Objects.requireNonNull(readingTimestamp, "readingTimestamp");
        this.voltage = voltage;
        this.current = current;
        this.powerWatts = powerWatts;
    }

    /**
     * Creates a reading from a meter-side timestamp expressed as epoch milliseconds — the
     * form the wire protocol's {@code T} field carries.
     *
     * @param deviceId    id of the device that produced the reading
     * @param epochMillis milliseconds since 1970-01-01T00:00:00Z
     * @param voltage     RMS voltage in volts
     * @param current     RMS current in amperes
     * @param powerWatts  real power in watts
     * @return the reading
     */
    public static Reading fromEpochMillis(int deviceId, long epochMillis, double voltage,
                                          double current, double powerWatts) {
        return new Reading(deviceId, Instant.ofEpochMilli(epochMillis), voltage, current, powerWatts);
    }

    /** @return id of the device that produced this reading. */
    public int getDeviceId() {
        return deviceId;
    }

    /** @return the instant of measurement, as reported by the meter. */
    public Instant getReadingTimestamp() {
        return readingTimestamp;
    }

    /** @return the meter-side timestamp as epoch milliseconds, for the wire format. */
    public long getReadingEpochMillis() {
        return readingTimestamp.toEpochMilli();
    }

    /** @return RMS voltage in volts. */
    public double getVoltage() {
        return voltage;
    }

    /** @return RMS current in amperes. */
    public double getCurrent() {
        return current;
    }

    /** @return real power in watts. */
    public double getPowerWatts() {
        return powerWatts;
    }

    /**
     * Returns the value of the given metric, so a {@code DetectionRule} can be written
     * against whichever metric its threshold bounds without switching over accessors.
     *
     * @param metric which quantity to read; must not be null
     * @return the measured value of that metric
     * @throws NullPointerException if {@code metric} is null
     */
    public double valueOf(Metric metric) {
        return switch (Objects.requireNonNull(metric, "metric")) {
            case VOLTAGE -> voltage;
            case CURRENT -> current;
            case POWER -> powerWatts;
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Reading other)) {
            return false;
        }
        return deviceId == other.deviceId
                && Double.compare(voltage, other.voltage) == 0
                && Double.compare(current, other.current) == 0
                && Double.compare(powerWatts, other.powerWatts) == 0
                && readingTimestamp.equals(other.readingTimestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, readingTimestamp, voltage, current, powerWatts);
    }

    @Override
    public String toString() {
        return "Reading[device=" + deviceId
                + ", ts=" + readingTimestamp
                + ", V=" + voltage
                + ", I=" + current
                + ", P=" + powerWatts + "]";
    }
}
