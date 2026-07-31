package com.smarthome.energy.client.model;

import com.smarthome.energy.model.Device;
import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.Reading;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The <em>Model</em> of the dashboard's MVC structure: the observable application state.
 *
 * <p>Holds the current {@link ApplianceState} for every device plus a rolling window of
 * recent readings and events. It exposes change notifications (listener callbacks) that the
 * view subscribes to, so the UI never reaches into raw data — it only reacts to model
 * updates. The controller mutates this model; the view reads it.</p>
 *
 * <h2>Every mutator asserts it is on the event dispatch thread</h2>
 *
 * <p>Readings arrive on the live feed's socket thread and history arrives on a
 * {@code SwingWorker}'s background thread, so there are two obvious places to accidentally
 * mutate shared state from the wrong thread. Swing components are not thread-safe, and the
 * symptom of getting this wrong is not an exception — it is a repaint that goes missing once
 * an hour on someone else's machine. {@link #requireEventDispatchThread()} turns that into
 * an immediate, loud failure at the point of the mistake, which is the only way this class
 * of bug gets found in a project of this length.</p>
 *
 * <p>The consequence is a rule the controller follows: everything that touches this model
 * goes through {@code SwingUtilities.invokeLater}.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming with Swing/AWT (MVC, observer pattern).</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
public final class DashboardModel {

    /** Alerts kept in memory for the event log. */
    public static final int MAX_EVENTS = 500;

    /**
     * What the view listens for. Every method has a no-op default so a panel implements only
     * the notifications it actually reacts to.
     */
    public interface Listener {

        /**
         * @param state the appliance whose reading just changed
         */
        default void applianceUpdated(ApplianceState state) {
            // Not interested by default.
        }

        /**
         * @param appliances the full appliance set, after it was rebuilt from the catalogue
         */
        default void appliancesReset(List<ApplianceState> appliances) {
            // Not interested by default.
        }

        /**
         * @param events the alert log, newest first
         */
        default void eventsChanged(List<Event> events) {
            // Not interested by default.
        }

        /**
         * @param deviceId the device the history belongs to
         * @param readings the history, oldest first
         */
        default void historyChanged(int deviceId, List<Reading> readings) {
            // Not interested by default.
        }

        /**
         * @param connected whether the live feed is currently up
         * @param detail    a short human-readable explanation
         */
        default void connectionChanged(boolean connected, String detail) {
            // Not interested by default.
        }

        /**
         * @param message what the dashboard is doing or failed to do
         */
        default void statusChanged(String message) {
            // Not interested by default.
        }
    }

    // Keyed in device-id order, not insertion order: without a catalogue the appliances appear
    // in whatever order their first reading happens to arrive, and a tile grid that reshuffles
    // itself between runs is one an operator cannot learn the shape of.
    private final Map<Integer, ApplianceState> appliances = new TreeMap<>();
    private final List<Event> events = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private List<Reading> history = List.of();
    private int historyDeviceId;
    private boolean connected;
    private String connectionDetail = "not connected";
    private String status = "starting";
    private long readingsReceived;

    /**
     * @param listener the view component to notify; must not be null
     * @throws NullPointerException if {@code listener} is null
     */
    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Replaces the appliance set from the device catalogue, keeping any live state already
     * gathered for a device that is in both.
     *
     * @param devices the catalogue; must not be null
     * @throws NullPointerException  if {@code devices} is null
     * @throws IllegalStateException if called off the event dispatch thread
     */
    public void setCatalogue(Collection<Device> devices) {
        requireEventDispatchThread();
        Objects.requireNonNull(devices, "devices");
        for (Device device : devices) {
            ApplianceState existing = appliances.get(device.getDeviceId());
            if (existing == null) {
                appliances.put(device.getDeviceId(), new ApplianceState(device.getDeviceId(), device.getName()));
            } else {
                existing.setName(device.getName());
            }
        }
        List<ApplianceState> snapshot = getAppliances();
        listeners.forEach(listener -> listener.appliancesReset(snapshot));
    }

    /**
     * Applies a reading that has just arrived on the live feed, creating the appliance if the
     * catalogue has not been loaded (which is the case when the dashboard runs without a
     * database).
     *
     * @param reading the reading; must not be null
     * @throws NullPointerException  if {@code reading} is null
     * @throws IllegalStateException if called off the event dispatch thread
     */
    public void applyReading(Reading reading) {
        requireEventDispatchThread();
        Objects.requireNonNull(reading, "reading");

        ApplianceState state = appliances.get(reading.getDeviceId());
        boolean isNew = state == null;
        if (isNew) {
            state = new ApplianceState(reading.getDeviceId(), "Device " + reading.getDeviceId());
            appliances.put(reading.getDeviceId(), state);
        }
        state.apply(reading);
        readingsReceived++;

        if (isNew) {
            List<ApplianceState> snapshot = getAppliances();
            listeners.forEach(listener -> listener.appliancesReset(snapshot));
        } else {
            ApplianceState updated = state;
            listeners.forEach(listener -> listener.applianceUpdated(updated));
        }
    }

    /**
     * Replaces the alert log.
     *
     * @param newest the alerts, newest first; must not be null
     * @throws NullPointerException  if {@code newest} is null
     * @throws IllegalStateException if called off the event dispatch thread
     */
    public void setEvents(List<Event> newest) {
        requireEventDispatchThread();
        Objects.requireNonNull(newest, "newest");
        events.clear();
        events.addAll(newest.size() > MAX_EVENTS ? newest.subList(0, MAX_EVENTS) : newest);
        List<Event> snapshot = getEvents();
        listeners.forEach(listener -> listener.eventsChanged(snapshot));
    }

    /**
     * Applies an alert that just arrived on the live feed: it enters the log and colours the
     * offending appliance's tile.
     *
     * <p>An alert for a device with no tile — one whose readings the dashboard has not seen —
     * is still logged. The alert log is the record of what happened, and dropping entries
     * because the window has not met the device yet would be losing exactly the information
     * the operator opened it for.</p>
     *
     * @param event the alert; must not be null
     * @throws NullPointerException  if {@code event} is null
     * @throws IllegalStateException if called off the event dispatch thread
     */
    public void applyAlert(Event event) {
        requireEventDispatchThread();
        Objects.requireNonNull(event, "event");

        ApplianceState state = appliances.get(event.getDeviceId());
        if (state != null) {
            state.raiseAlert(event.getSeverity());
            listeners.forEach(listener -> listener.applianceUpdated(state));
        }
        addEvent(event);
    }

    /**
     * Adds one alert to the top of the log, without touching any appliance's state. The alert
     * channel goes through {@link #applyAlert(Event)}; this is the log on its own.
     *
     * @param event the alert; must not be null
     * @throws NullPointerException  if {@code event} is null
     * @throws IllegalStateException if called off the event dispatch thread
     */
    public void addEvent(Event event) {
        requireEventDispatchThread();
        events.add(0, Objects.requireNonNull(event, "event"));
        while (events.size() > MAX_EVENTS) {
            events.remove(events.size() - 1);
        }
        List<Event> snapshot = getEvents();
        listeners.forEach(listener -> listener.eventsChanged(snapshot));
    }

    /**
     * Replaces the history series shown by the history chart.
     *
     * @param deviceId the device the series belongs to
     * @param readings the readings, oldest first; must not be null
     * @throws NullPointerException  if {@code readings} is null
     * @throws IllegalStateException if called off the event dispatch thread
     */
    public void setHistory(int deviceId, List<Reading> readings) {
        requireEventDispatchThread();
        this.historyDeviceId = deviceId;
        this.history = List.copyOf(Objects.requireNonNull(readings, "readings"));
        listeners.forEach(listener -> listener.historyChanged(deviceId, history));
    }

    /**
     * Records whether the live feed is up.
     *
     * @param connected whether the feed is connected
     * @param detail    a short explanation for the status bar; must not be null
     * @throws NullPointerException  if {@code detail} is null
     * @throws IllegalStateException if called off the event dispatch thread
     */
    public void setConnected(boolean connected, String detail) {
        requireEventDispatchThread();
        this.connected = connected;
        this.connectionDetail = Objects.requireNonNull(detail, "detail");
        listeners.forEach(listener -> listener.connectionChanged(connected, detail));
    }

    /**
     * Records what the dashboard is currently doing.
     *
     * @param status the message for the status bar; must not be null
     * @throws NullPointerException  if {@code status} is null
     * @throws IllegalStateException if called off the event dispatch thread
     */
    public void setStatus(String status) {
        requireEventDispatchThread();
        this.status = Objects.requireNonNull(status, "status");
        listeners.forEach(listener -> listener.statusChanged(status));
    }

    /** @return the appliances, in device-id order of first appearance. */
    public List<ApplianceState> getAppliances() {
        return List.copyOf(appliances.values());
    }

    /**
     * @param deviceId the device to look up
     * @return that appliance's state, or null if no reading has ever arrived for it
     */
    public ApplianceState getAppliance(int deviceId) {
        return appliances.get(deviceId);
    }

    /** @return the alert log, newest first. */
    public List<Event> getEvents() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    /** @return the history series currently charted, oldest first. */
    public List<Reading> getHistory() {
        return history;
    }

    /** @return the device the charted history belongs to, or 0 if none has been loaded. */
    public int getHistoryDeviceId() {
        return historyDeviceId;
    }

    /** @return whether the live feed is up. */
    public boolean isConnected() {
        return connected;
    }

    /** @return the short explanation behind {@link #isConnected()}. */
    public String getConnectionDetail() {
        return connectionDetail;
    }

    /** @return what the dashboard is currently doing. */
    public String getStatus() {
        return status;
    }

    /** @return how many readings have arrived on the live feed since the window opened. */
    public long getReadingsReceived() {
        return readingsReceived;
    }

    /**
     * Fails loudly if a mutation is attempted from anywhere but the event dispatch thread.
     *
     * @throws IllegalStateException if the calling thread is not the EDT
     */
    private static void requireEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("the dashboard model may only be changed on the Swing "
                    + "event dispatch thread, but this call came from '"
                    + Thread.currentThread().getName() + "'. Wrap it in SwingUtilities.invokeLater.");
        }
    }
}
