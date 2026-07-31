package com.smarthome.energy.rules;

import com.smarthome.energy.db.DeviceDao;
import com.smarthome.energy.db.ThresholdDao;
import com.smarthome.energy.model.Device;
import com.smarthome.energy.model.Metric;
import com.smarthome.energy.model.Threshold;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only snapshot of the reference data a {@link DetectionRule} needs to make a
 * decision: the thresholds in force and the metadata of the device being evaluated.
 *
 * <p>Passing a context object (rather than querying the database inside each rule) keeps
 * rules pure and fast — they operate only on values already in memory. The
 * {@link RuleEngine} builds and refreshes the context from {@link ThresholdDao} and
 * {@link DeviceDao}.</p>
 *
 * <h2>Why the whole table is loaded once</h2>
 *
 * <p>Every reading is evaluated by three rules, so a lookup that went to the database would
 * add three round-trips per reading per meter to a path that also has to write. The
 * thresholds table has one row per device and metric — a few dozen rows — so it is read
 * once at start-up and re-read only when {@link RuleEngine#reload(RuleContext)} says to.</p>
 *
 * <h2>Resolution order</h2>
 *
 * <p>A device-specific row wins over the global default ({@code device_id IS NULL}) for the
 * same metric. If neither exists the metric is unbounded and the rules reading it decline to
 * fire: a missing threshold means nobody has said what is normal here, and inventing a limit
 * would raise alerts no one configured.</p>
 *
 * <p>Instances are immutable once built, which is what makes them safe to hand to the
 * dispatcher's worker threads and to swap wholesale on a reload.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals.</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public final class RuleContext {

    private final Map<Integer, Device> devices;
    private final Map<Integer, Map<Metric, Threshold>> overrides;
    private final Map<Metric, Threshold> defaults;

    /**
     * Builds a context from already-loaded reference data, which is how the tests build one
     * and how {@link #load(DeviceDao, ThresholdDao)} finishes.
     *
     * @param devices    the appliance catalogue; must not be null
     * @param thresholds every threshold row, global defaults included; must not be null
     * @throws NullPointerException if either argument is null
     */
    public RuleContext(Collection<Device> devices, Collection<Threshold> thresholds) {
        Objects.requireNonNull(devices, "devices");
        Objects.requireNonNull(thresholds, "thresholds");

        Map<Integer, Device> byId = new HashMap<>();
        for (Device device : devices) {
            byId.put(device.getDeviceId(), device);
        }

        Map<Integer, Map<Metric, Threshold>> perDevice = new HashMap<>();
        Map<Metric, Threshold> global = new EnumMap<>(Metric.class);
        for (Threshold threshold : thresholds) {
            if (threshold.isGlobalDefault()) {
                global.put(threshold.getMetric(), threshold);
            } else {
                perDevice.computeIfAbsent(threshold.getDeviceId(), id -> new EnumMap<>(Metric.class))
                        .put(threshold.getMetric(), threshold);
            }
        }

        this.devices = Map.copyOf(byId);
        this.overrides = Map.copyOf(perDevice);
        this.defaults = Map.copyOf(global);
    }

    /**
     * Reads the catalogue and the thresholds from the database.
     *
     * @param devices    the device DAO; must not be null
     * @param thresholds the threshold DAO; must not be null
     * @return a context holding everything the rules need
     * @throws com.smarthome.energy.db.DataAccessException if either query fails
     * @throws NullPointerException                        if either argument is null
     */
    public static RuleContext load(DeviceDao devices, ThresholdDao thresholds) {
        Objects.requireNonNull(devices, "devices");
        Objects.requireNonNull(thresholds, "thresholds");
        return new RuleContext(devices.findAll(), thresholds.findAll());
    }

    /**
     * Resolves the limit in force for one device and metric.
     *
     * @param deviceId the device being evaluated
     * @param metric   the quantity being bounded; must not be null
     * @return the device's own row, else the global default, else empty
     * @throws NullPointerException if {@code metric} is null
     */
    public Optional<Threshold> thresholdFor(int deviceId, Metric metric) {
        Objects.requireNonNull(metric, "metric");
        Threshold specific = overrides.getOrDefault(deviceId, Map.of()).get(metric);
        return Optional.ofNullable(specific != null ? specific : defaults.get(metric));
    }

    /**
     * @param deviceId the device to look up
     * @return the device's catalogue entry, or empty if it is not in the catalogue
     */
    public Optional<Device> device(int deviceId) {
        return Optional.ofNullable(devices.get(deviceId));
    }

    /**
     * @param deviceId the device to name
     * @return the device's display name, or {@code "device N"} if it is not in the catalogue.
     *         Alert details are read by a person, and "Kitchen Refrigerator" says more than 1
     */
    public String deviceName(int deviceId) {
        return device(deviceId).map(Device::getName).orElse("device " + deviceId);
    }

    /** @return how many devices this context knows about. */
    public int getDeviceCount() {
        return devices.size();
    }

    /** @return how many threshold rows this context was built from, defaults included. */
    public int getThresholdCount() {
        int count = defaults.size();
        for (Map<Metric, Threshold> perDevice : overrides.values()) {
            count += perDevice.size();
        }
        return count;
    }

    @Override
    public String toString() {
        return "RuleContext[devices=" + devices.size()
                + ", thresholds=" + getThresholdCount()
                + " (" + defaults.size() + " global default)]";
    }
}
