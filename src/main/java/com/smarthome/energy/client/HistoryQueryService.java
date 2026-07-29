package com.smarthome.energy.client;

import com.smarthome.energy.db.ConnectionFactory;
import com.smarthome.energy.db.DeviceDao;
import com.smarthome.energy.db.EventDao;
import com.smarthome.energy.db.ReadingDao;
import com.smarthome.energy.model.Device;
import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.Reading;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Read-only JDBC access used by the dashboard to populate history charts and the alert log.
 *
 * <p>Where {@link LiveFeedClient} supplies the real-time stream, this service answers the
 * "what happened before I opened the window" questions by querying the database directly
 * through the {@code ReadingDao} and {@code EventDao}. Keeping historical reads in their
 * own service leaves the live path untouched.</p>
 *
 * <p>Every method here blocks on the network and on MySQL, so <strong>none of them may be
 * called from the event dispatch thread</strong>: the window would stop repainting until the
 * query returned, which on a large {@code readings} table is long enough to look like a
 * crash. The controller calls this only from inside a {@code SwingWorker}, and the same
 * query run both ways is one of the failure-mode demonstrations in the report.</p>
 *
 * <p>The class is read-only by design. The dashboard's one write — the Phase 4 threshold
 * editor — goes through {@code ThresholdDao} rather than here, so nothing on this path can
 * modify what it is displaying.</p>
 *
 * <p>Syllabus mapping: Unit III — Database connectivity via JDBC (read queries from the UI).</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
public final class HistoryQueryService {

    /** Alerts fetched for the log when the window opens. */
    public static final int RECENT_EVENT_LIMIT = 200;

    private final DeviceDao devices;
    private final ReadingDao readings;
    private final EventDao events;
    private final String url;

    /**
     * @param connections the factory the DAOs take their connections from; must not be null
     * @throws NullPointerException if {@code connections} is null
     */
    public HistoryQueryService(ConnectionFactory connections) {
        Objects.requireNonNull(connections, "connections");
        this.devices = new DeviceDao(connections);
        this.readings = new ReadingDao(connections);
        this.events = new EventDao(connections);
        this.url = connections.getUrl();
    }

    /**
     * Builds a service from {@code db.properties}.
     *
     * @return the service
     * @throws com.smarthome.energy.db.DataAccessException if the JDBC settings are missing
     */
    public static HistoryQueryService fromDefaultConfig() {
        return new HistoryQueryService(ConnectionFactory.fromDefaultConfig());
    }

    /** @return the database this service reads from, for the status bar. */
    public String getUrl() {
        return url;
    }

    /**
     * Reads the appliance catalogue, so tiles can be labelled before any reading arrives.
     *
     * @return every device, in id order
     * @throws com.smarthome.energy.db.DataAccessException if the query fails
     */
    public List<Device> loadCatalogue() {
        return devices.findAll();
    }

    /**
     * Reads one device's recent readings.
     *
     * @param deviceId the device to chart
     * @param window   how far back to look; must not be null
     * @return the readings in that window, oldest first
     * @throws NullPointerException                        if {@code window} is null
     * @throws com.smarthome.energy.db.DataAccessException if the query fails
     */
    public List<Reading> loadHistory(int deviceId, Duration window) {
        Objects.requireNonNull(window, "window");
        return readings.findByDeviceSince(deviceId, Instant.now().minus(window));
    }

    /**
     * Reads the most recent alerts across all devices.
     *
     * @return up to {@link #RECENT_EVENT_LIMIT} events, newest first
     * @throws com.smarthome.energy.db.DataAccessException if the query fails
     */
    public List<Event> loadRecentEvents() {
        return events.findRecent(RECENT_EVENT_LIMIT);
    }
}
