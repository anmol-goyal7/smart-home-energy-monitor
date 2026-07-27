package com.smarthome.energy.model;

import java.util.Objects;

/**
 * Value object describing a monitored appliance (one physical smart meter).
 *
 * <p>Mirrors a row of the {@code devices} table. Devices are relatively static
 * reference data: they are seeded once and referenced by every {@link Reading} and
 * {@link Event} through {@code deviceId}.</p>
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code deviceId} — surrogate primary key.</li>
 *   <li>{@code name} — human-readable label (e.g. "Kitchen Refrigerator").</li>
 *   <li>{@code applianceType} — category used for grouping/analytics.</li>
 *   <li>{@code location} — room or circuit.</li>
 *   <li>{@code ratedVoltage} — nominal operating voltage.</li>
 *   <li>{@code ratedPowerWatts} — manufacturer power rating, a baseline for overload rules.</li>
 * </ul>
 *
 * <p>Unlike {@link Reading}, a device does carry its key: devices are read far more often
 * than they are created, and every caller that holds one obtained it from the database, so
 * the id is meaningful. On the single path where it is not — {@code DeviceDao.insert} — the
 * field is ignored and the generated key returned instead; {@link #unsaved} makes that
 * intent explicit at the call site.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals; Unit III — maps to a JDBC entity.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class Device {

    /** Sentinel {@code deviceId} for a device that has not been persisted yet. */
    public static final int UNSAVED_ID = 0;

    private final int deviceId;
    private final String name;
    private final String applianceType;
    private final String location;
    private final double ratedVoltage;
    private final double ratedPowerWatts;

    /**
     * Creates a device.
     *
     * @param deviceId        surrogate key, or {@link #UNSAVED_ID} if not yet persisted
     * @param name            human-readable label; must not be null
     * @param applianceType   category used for grouping; must not be null
     * @param location        room or circuit; must not be null
     * @param ratedVoltage    nominal operating voltage in volts
     * @param ratedPowerWatts manufacturer power rating in watts
     * @throws NullPointerException if any string argument is null
     */
    public Device(int deviceId, String name, String applianceType, String location,
                  double ratedVoltage, double ratedPowerWatts) {
        this.deviceId = deviceId;
        this.name = Objects.requireNonNull(name, "name");
        this.applianceType = Objects.requireNonNull(applianceType, "applianceType");
        this.location = Objects.requireNonNull(location, "location");
        this.ratedVoltage = ratedVoltage;
        this.ratedPowerWatts = ratedPowerWatts;
    }

    /**
     * Creates a device that has not been persisted yet, for passing to
     * {@code DeviceDao.insert}.
     *
     * @param name            human-readable label
     * @param applianceType   category used for grouping
     * @param location        room or circuit
     * @param ratedVoltage    nominal operating voltage in volts
     * @param ratedPowerWatts manufacturer power rating in watts
     * @return the device, carrying {@link #UNSAVED_ID} as its id
     */
    public static Device unsaved(String name, String applianceType, String location,
                                 double ratedVoltage, double ratedPowerWatts) {
        return new Device(UNSAVED_ID, name, applianceType, location, ratedVoltage, ratedPowerWatts);
    }

    /** @return the surrogate primary key, or {@link #UNSAVED_ID} if not yet persisted. */
    public int getDeviceId() {
        return deviceId;
    }

    /** @return the human-readable label. */
    public String getName() {
        return name;
    }

    /** @return the appliance category. */
    public String getApplianceType() {
        return applianceType;
    }

    /** @return the room or circuit the appliance sits on. */
    public String getLocation() {
        return location;
    }

    /** @return the nominal operating voltage in volts. */
    public double getRatedVoltage() {
        return ratedVoltage;
    }

    /** @return the manufacturer power rating in watts. */
    public double getRatedPowerWatts() {
        return ratedPowerWatts;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Device other)) {
            return false;
        }
        return deviceId == other.deviceId
                && Double.compare(ratedVoltage, other.ratedVoltage) == 0
                && Double.compare(ratedPowerWatts, other.ratedPowerWatts) == 0
                && name.equals(other.name)
                && applianceType.equals(other.applianceType)
                && location.equals(other.location);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, name, applianceType, location, ratedVoltage, ratedPowerWatts);
    }

    @Override
    public String toString() {
        return "Device[" + deviceId + ", " + name + " (" + applianceType + ") in " + location
                + ", rated " + ratedPowerWatts + "W @ " + ratedVoltage + "V]";
    }
}
