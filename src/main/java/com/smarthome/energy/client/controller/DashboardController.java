package com.smarthome.energy.client.controller;

import com.smarthome.energy.client.HistoryQueryService;
import com.smarthome.energy.client.LiveFeedClient;
import com.smarthome.energy.client.ThresholdService;
import com.smarthome.energy.client.model.DashboardModel;
import com.smarthome.energy.db.DataAccessException;
import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.Reading;
import com.smarthome.energy.model.Threshold;
import com.smarthome.energy.protocol.MeterMessage;

import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * The <em>Controller</em> of the dashboard's MVC structure.
 *
 * <p>Sits between the network/data layer and the model. It receives readings from the
 * {@link LiveFeedClient} and history from {@link HistoryQueryService}, translates them into
 * model updates ({@link DashboardModel}), and handles user gestures from the view (e.g.
 * selecting which appliance to chart). It ensures all model mutations that the view observes
 * happen on the Swing event dispatch thread.</p>
 *
 * <h2>The two threading rules this class exists to enforce</h2>
 *
 * <p><strong>Nothing touches the model off the EDT.</strong> Readings arrive on the live
 * feed's socket thread, so every one of them is applied inside
 * {@code SwingUtilities.invokeLater}. The model asserts this independently, so a future
 * change that forgets the rule fails immediately rather than corrupting a repaint.</p>
 *
 * <p><strong>Nothing blocks on the EDT.</strong> Every database read happens inside a
 * {@link SwingWorker}: {@code doInBackground} runs the query on a worker thread and
 * {@code done} publishes the result back on the EDT. Running the same query directly from an
 * action listener freezes the window until it returns — which is the point of the
 * demonstration in the report, and the reason it is not how this code works.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming with Swing/AWT (MVC controller, event
 * handling, EDT marshalling).</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
public final class DashboardController implements LiveFeedClient.Listener {

    /** History window used until the operator picks another. */
    public static final Duration DEFAULT_HISTORY_WINDOW = Duration.ofMinutes(15);

    private final DashboardModel model;
    private final HistoryQueryService history;
    private final ThresholdService thresholds;

    private Duration historyWindow = DEFAULT_HISTORY_WINDOW;
    private int selectedDeviceId;
    private LiveFeedClient feed;

    /**
     * Creates a controller with database-backed history and alerts, but no threshold editing.
     *
     * @param model   the model to mutate; must not be null
     * @param history the JDBC read service; must not be null
     * @throws NullPointerException if either argument is null
     */
    public DashboardController(DashboardModel model, HistoryQueryService history) {
        this(model, history, null);
    }

    /**
     * Creates a controller with history, alerts, and threshold editing.
     *
     * @param model      the model to mutate; must not be null
     * @param history    the JDBC read service; must not be null
     * @param thresholds the threshold read/write service, or null to leave the editor
     *                   read-only
     * @throws NullPointerException if {@code model} or {@code history} is null
     */
    public DashboardController(DashboardModel model, HistoryQueryService history,
                               ThresholdService thresholds) {
        this.model = Objects.requireNonNull(model, "model");
        this.history = Objects.requireNonNull(history, "history");
        this.thresholds = thresholds;
    }

    /**
     * Creates a controller with the live feed only.
     *
     * <p>The tiles and their sparklines work from the feed alone; the history chart and the
     * alert log stay empty and say why, rather than the window failing to open because MySQL
     * is not running.</p>
     *
     * @param model the model to mutate; must not be null
     * @throws NullPointerException if {@code model} is null
     */
    public DashboardController(DashboardModel model) {
        this.model = Objects.requireNonNull(model, "model");
        this.history = null;
        this.thresholds = null;
    }

    /** @return true if this dashboard has a database to read history and alerts from. */
    public boolean hasDatabase() {
        return history != null;
    }

    /** @return true if this dashboard can read and commit detection thresholds. */
    public boolean canEditThresholds() {
        return thresholds != null;
    }

    /**
     * Gives the controller the feed it should send commands up.
     *
     * <p>Set after construction because the feed needs this controller as its listener, so one
     * of the two has to exist first. The threshold editor is disabled until this is called.</p>
     *
     * @param feed the live feed; may be null to detach
     */
    public void setFeed(LiveFeedClient feed) {
        this.feed = feed;
    }

    /** @return the window the history chart currently covers. */
    public Duration getHistoryWindow() {
        return historyWindow;
    }

    /** @return the device the history chart is showing, or 0 if none has been selected. */
    public int getSelectedDeviceId() {
        return selectedDeviceId;
    }

    // ---------------------------------------------------------------- live feed

    /**
     * {@inheritDoc}
     *
     * <p>Called on the live feed's thread; hands the reading to the EDT.</p>
     */
    @Override
    public void readingReceived(Reading reading) {
        SwingUtilities.invokeLater(() -> model.applyReading(reading));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Called on the live feed's thread; hands the alert to the EDT, where it both enters
     * the log and colours the offending appliance's tile.</p>
     */
    @Override
    public void alertReceived(Event alert) {
        SwingUtilities.invokeLater(() -> model.applyAlert(alert));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Called on the live feed's thread; hands the change to the EDT.</p>
     */
    @Override
    public void connectionStateChanged(boolean connected, String detail) {
        SwingUtilities.invokeLater(() -> model.setConnected(connected, detail));
    }

    // ---------------------------------------------------------------- user gestures

    /**
     * Loads the device catalogue so the tiles carry real names before any reading arrives.
     * Does nothing when the dashboard is running without a database.
     */
    public void loadCatalogue() {
        if (!hasDatabase()) {
            return;
        }
        runQuery("loading the device catalogue", history::loadCatalogue, devices -> {
            model.setCatalogue(devices);
            if (selectedDeviceId == 0 && !devices.isEmpty()) {
                selectDevice(devices.get(0).getDeviceId());
            }
            model.setStatus(devices.size() + " device(s) in the catalogue");
        });
    }

    /**
     * Refreshes the alert log from the {@code events} table. Does nothing without a database.
     */
    public void refreshEvents() {
        if (!hasDatabase()) {
            return;
        }
        runQuery("loading recent alerts", history::loadRecentEvents, (List<Event> events) -> {
            model.setEvents(events);
            model.setStatus(events.isEmpty() ? "no alerts recorded yet"
                    : events.size() + " alert(s) loaded");
        });
    }

    /**
     * Reloads the threshold table into the model. Does nothing without a database.
     */
    public void refreshThresholds() {
        if (!canEditThresholds()) {
            return;
        }
        runQuery("loading detection thresholds", thresholds::loadAll, loaded -> {
            model.setThresholds(loaded);
            model.setStatus(loaded.size() + " threshold(s) loaded");
        });
    }

    /**
     * Commits an edited threshold and asks the server to pick it up.
     *
     * <p>The two halves are deliberately sequential rather than concurrent: the reload is only
     * sent once the commit has returned, because a server that re-read the table before the
     * {@code UPDATE} landed would reload the old values and report success. The command's
     * acknowledgement then arrives asynchronously on the feed and is reported through
     * {@link #commandReplyReceived(String)}.</p>
     *
     * @param threshold the limits to store; must not be null
     * @throws NullPointerException if {@code threshold} is null
     */
    public void saveThreshold(Threshold threshold) {
        Objects.requireNonNull(threshold, "threshold");
        if (!canEditThresholds()) {
            model.setStatus("thresholds cannot be edited without a database");
            return;
        }
        String what = describe(threshold);
        runQuery("committing " + what, () -> {
            thresholds.save(threshold);
            return thresholds.loadAll();
        }, loaded -> {
            model.setThresholds(loaded);
            if (feed == null || !feed.sendCommand(MeterMessage.RELOAD_COMMAND)) {
                model.setStatus("committed " + what + ", but the live feed is down — the server "
                        + "is still evaluating against the old limits and will pick these up "
                        + "when it next starts");
                return;
            }
            model.setStatus("committed " + what + "; waiting for the server to reload…");
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Called on the live feed's thread; reports the server's answer to a reload in the
     * status bar. An edit is only really done once this has arrived saying so.</p>
     */
    @Override
    public void commandReplyReceived(String reply) {
        String message = MeterMessage.RELOAD_ACK.equals(reply)
                ? "server reloaded its thresholds — the next reading is evaluated against them"
                : "server refused the reload: " + reply;
        SwingUtilities.invokeLater(() -> model.setStatus(message));
    }

    /** Names a threshold the way the status bar should say it. */
    private static String describe(Threshold threshold) {
        String scope = threshold.isGlobalDefault()
                ? "the default"
                : "device " + threshold.getDeviceId() + "'s";
        return scope + " " + threshold.getMetric().name().toLowerCase(Locale.ROOT) + " limits";
    }

    /**
     * Charts a different device's history.
     *
     * @param deviceId the device to chart
     */
    public void selectDevice(int deviceId) {
        this.selectedDeviceId = deviceId;
        refreshHistory();
    }

    /**
     * Changes how far back the history chart looks.
     *
     * @param window the new window; must not be null
     * @throws NullPointerException if {@code window} is null
     */
    public void setHistoryWindow(Duration window) {
        this.historyWindow = Objects.requireNonNull(window, "window");
        refreshHistory();
    }

    /** Re-runs the history query for the selected device and window. */
    public void refreshHistory() {
        if (!hasDatabase() || selectedDeviceId == 0) {
            return;
        }
        int deviceId = selectedDeviceId;
        Duration window = historyWindow;
        runQuery("loading history for device " + deviceId,
                () -> history.loadHistory(deviceId, window),
                readings -> {
                    model.setHistory(deviceId, readings);
                    model.setStatus(readings.size() + " reading(s) over the last "
                            + describe(window) + " for device " + deviceId);
                });
    }

    /** @param window a duration to name in the status bar */
    private static String describe(Duration window) {
        long minutes = window.toMinutes();
        return minutes < 60 ? minutes + " minutes" : (minutes / 60) + " hours";
    }

    /**
     * Runs a blocking query on a worker thread and applies its result on the EDT.
     *
     * <p>This is the whole of the project's {@code SwingWorker} usage in one place: the
     * alternative — every action listener spawning its own — is how a UI ends up with three
     * different threading conventions and one of them wrong.</p>
     *
     * @param description what the query is doing, for the status bar
     * @param query       the blocking call; runs on a background thread
     * @param onSuccess   what to do with the result; runs on the EDT
     * @param <T>         the result type
     */
    private <T> void runQuery(String description, Query<T> query, Consumer<T> onSuccess) {
        model.setStatus(description + "…");
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() {
                return query.run();
            }

            @Override
            protected void done() {
                try {
                    onSuccess.accept(get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String reason = cause instanceof DataAccessException
                            ? cause.getMessage()
                            : String.valueOf(cause);
                    model.setStatus("failed " + description + ": " + reason);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    /**
     * A blocking database call, run off the event dispatch thread.
     *
     * @param <T> what the call returns
     */
    @FunctionalInterface
    private interface Query<T> {

        /** @return the query result */
        T run();
    }
}
