package com.smarthome.energy.client.model;

import com.smarthome.energy.model.Reading;
import com.smarthome.energy.model.Severity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * The live state of a single appliance as shown on the dashboard.
 *
 * <p>Tracks the latest voltage/current/power for one device, a short rolling history for
 * its sparkline, and its current alert status. One {@code ApplianceState} backs one
 * appliance panel in the view.</p>
 *
 * <p>The rolling history is a fixed-length deque, not a growing list. A dashboard left open
 * overnight would otherwise accumulate tens of thousands of samples per appliance to draw a
 * sparkline two hundred pixels wide; capping it at {@link #HISTORY_SAMPLES} keeps the memory
 * flat and the paint cost constant, and the longer view is what the history chart and its
 * JDBC query are for.</p>
 *
 * <p>Instances are mutated only on the Swing event dispatch thread, by
 * {@link DashboardModel}. They are deliberately not synchronised: confinement to one thread
 * is the guarantee Swing already requires, and adding locks on top would suggest the state
 * can safely be touched from elsewhere, which it cannot.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming with Swing/AWT (MVC model element).</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
public final class ApplianceState {

    /** Power samples kept for the sparkline — about two minutes at one reading a second. */
    public static final int HISTORY_SAMPLES = 120;

    /** How long without a reading before an appliance is shown as stale. */
    public static final Duration STALE_AFTER = Duration.ofSeconds(5);

    /**
     * How long a tile stays coloured after its last alert.
     *
     * <p>An alert arrives immediately behind the reading that caused it, so clearing the
     * colour on the next reading would make a sustained fault flash green once a second —
     * the tile would spend half its life claiming the appliance was fine. Holding the colour
     * for a few readings means the tile is red for as long as the condition lasts and returns
     * to normal a moment after it stops.</p>
     */
    public static final Duration ALERT_HOLD = Duration.ofSeconds(3);

    private final int deviceId;
    private String name;
    private final Deque<Double> recentPower = new ArrayDeque<>(HISTORY_SAMPLES);

    private Reading latest;
    private Instant lastUpdatedAt;
    private long readingCount;
    private double peakPower;
    private Severity alertSeverity;
    private Instant alertedAt;

    /**
     * @param deviceId the device this state belongs to; must be positive
     * @param name     display label; must not be null
     * @throws IllegalArgumentException if {@code deviceId} is not positive
     * @throws NullPointerException     if {@code name} is null
     */
    public ApplianceState(int deviceId, String name) {
        if (deviceId <= 0) {
            throw new IllegalArgumentException("deviceId must be positive, was " + deviceId);
        }
        this.deviceId = deviceId;
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * Records a newly arrived reading.
     *
     * @param reading the reading; must not be null and must belong to this device
     * @throws NullPointerException     if {@code reading} is null
     * @throws IllegalArgumentException if the reading is for a different device
     */
    public void apply(Reading reading) {
        Objects.requireNonNull(reading, "reading");
        if (reading.getDeviceId() != deviceId) {
            throw new IllegalArgumentException("reading for device " + reading.getDeviceId()
                    + " applied to the state of device " + deviceId);
        }
        this.latest = reading;
        // Arrival time, not the meter's timestamp: staleness is a statement about the feed,
        // and a meter with a wrong clock should not be able to look permanently fresh.
        this.lastUpdatedAt = Instant.now();
        this.readingCount++;
        clearExpiredAlert(lastUpdatedAt);
        this.peakPower = Math.max(peakPower, reading.getPowerWatts());

        if (recentPower.size() == HISTORY_SAMPLES) {
            recentPower.removeFirst();
        }
        recentPower.addLast(reading.getPowerWatts());
    }

    /** @return the device this state belongs to. */
    public int getDeviceId() {
        return deviceId;
    }

    /** @return the display label. */
    public String getName() {
        return name;
    }

    /**
     * Renames the appliance, used once the device catalogue has been read from the database.
     *
     * @param name the label to display; must not be null
     * @throws NullPointerException if {@code name} is null
     */
    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /** @return the most recent reading, or null if none has arrived yet. */
    public Reading getLatest() {
        return latest;
    }

    /** @return when the most recent reading arrived, or null if none has. */
    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    /** @return how many readings this appliance has sent since the dashboard opened. */
    public long getReadingCount() {
        return readingCount;
    }

    /** @return the highest power seen since the dashboard opened, in watts. */
    public double getPeakPower() {
        return peakPower;
    }

    /**
     * @return the severity of the appliance's currently active alert, or null if it has none.
     *         The panel colours itself from this
     */
    public Severity getAlertSeverity() {
        return alertSeverity;
    }

    /**
     * Marks this appliance as alerting.
     *
     * <p>Within the {@link #ALERT_HOLD} window the most severe alert wins: a reading that is
     * both a sag and an overload raises two events, and a tile that showed whichever arrived
     * last would understate the situation half the time.</p>
     *
     * @param severity the severity of the alert just received; must not be null
     * @throws NullPointerException if {@code severity} is null
     */
    public void raiseAlert(Severity severity) {
        Objects.requireNonNull(severity, "severity");
        // The dashboard's own clock, not the event's detectedAt: the hold is about how long
        // ago this window heard about the alert, and the server's clock may not be this one.
        Instant now = Instant.now();
        clearExpiredAlert(now);
        if (alertSeverity == null || severity.compareTo(alertSeverity) > 0) {
            this.alertSeverity = severity;
        }
        this.alertedAt = now;
    }

    /**
     * @param alertSeverity the severity to show, or null to clear the alert state. Used by the
     *                      tests and by anything that needs to set the state outright rather
     *                      than by receiving an alert
     */
    public void setAlertSeverity(Severity alertSeverity) {
        this.alertSeverity = alertSeverity;
        this.alertedAt = alertSeverity == null ? null : Instant.now();
    }

    /** Drops the alert colour once nothing has re-raised it for {@link #ALERT_HOLD}. */
    private void clearExpiredAlert(Instant now) {
        if (alertSeverity != null && alertedAt != null
                && Duration.between(alertedAt, now).compareTo(ALERT_HOLD) > 0) {
            this.alertSeverity = null;
            this.alertedAt = null;
        }
    }

    /**
     * @param now the current instant
     * @return true if no reading has arrived within {@link #STALE_AFTER}
     */
    public boolean isStale(Instant now) {
        return lastUpdatedAt == null || Duration.between(lastUpdatedAt, now).compareTo(STALE_AFTER) > 0;
    }

    /**
     * The rolling power history, oldest first.
     *
     * @return a copy of the recent samples, in watts; empty until the first reading arrives
     */
    public double[] getRecentPower() {
        double[] samples = new double[recentPower.size()];
        int i = 0;
        for (double watts : recentPower) {
            samples[i++] = watts;
        }
        return samples;
    }

    @Override
    public String toString() {
        return "ApplianceState[" + deviceId + " " + name + ", readings=" + readingCount
                + ", latest=" + latest + "]";
    }
}
