package com.smarthome.energy.client.view;

import com.smarthome.energy.client.controller.DashboardController;
import com.smarthome.energy.client.model.ApplianceState;
import com.smarthome.energy.client.model.DashboardModel;
import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.Reading;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The <em>View</em> of the dashboard's MVC structure: the top-level Swing window.
 *
 * <p>Owns the {@code JFrame} and lays out the child panels — one {@link AppliancePanel}
 * per device, the {@link HistoryChartPanel}, and the {@link EventLogPanel}. The view
 * observes the {@link DashboardModel} and repaints on the Swing event dispatch thread when
 * the model changes; it holds no business logic and never talks to the network or database
 * directly.</p>
 *
 * <p>User gestures — picking an appliance, changing the history window, hitting refresh —
 * are forwarded to the {@link DashboardController}, which owns what happens next. The view
 * never queries anything itself, which is what keeps the "no blocking work on the EDT" rule
 * enforceable in one place instead of in every listener.</p>
 *
 * <h2>The one-second timer</h2>
 *
 * <p>A tile shows how long it has been since its appliance last reported, and an appliance
 * that has gone silent stops generating the very events that would repaint it. A
 * {@code javax.swing.Timer} — which fires on the EDT, unlike {@code java.util.Timer} —
 * repaints the tiles once a second so silence is visible rather than looking like a frozen
 * last reading.</p>
 *
 * <p>The palette is declared here because the window owns the look; the panels read these
 * constants so the tiles, the chart, and the table cannot drift into three different greys.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming with Swing/AWT (containers, layout, EDT).</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
public final class DashboardView implements DashboardModel.Listener {

    /** Window background. */
    public static final Color BACKGROUND = new Color(0x1E2126);

    /** Card and panel background. */
    public static final Color PANEL = new Color(0x272B31);

    /** Primary text. */
    public static final Color TEXT = new Color(0xE6E8EB);

    /** Secondary text: units, labels, timestamps. */
    public static final Color MUTED = new Color(0x9AA3AD);

    /** Lines, borders, and gridlines. */
    public static final Color GRID = new Color(0x3A3F47);

    /** Charts and informational highlights. */
    public static final Color ACCENT = new Color(0x4FC3F7);

    /** A healthy, reporting appliance. */
    public static final Color OK = new Color(0x66BB6A);

    /** A threshold crossed by a modest margin. */
    public static final Color WARNING = new Color(0xFFB300);

    /** A threshold crossed by a large margin. */
    public static final Color CRITICAL = new Color(0xEF5350);

    /** An appliance that has stopped reporting. */
    public static final Color STALE = new Color(0x6B7280);

    /** Tiles per row in the appliance grid. */
    private static final int GRID_COLUMNS = 3;

    /** How often the tiles are repainted so "seconds since last reading" stays true. */
    private static final int TICK_MS = 1_000;

    /** The windows offered by the history selector. */
    private static final List<Duration> HISTORY_WINDOWS = List.of(
            Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1),
            Duration.ofHours(6), Duration.ofHours(24));

    private final DashboardModel model;
    private final DashboardController controller;

    private final JFrame frame = new JFrame("Smart Home Energy Monitor");
    private final JPanel applianceGrid = new JPanel(new GridLayout(0, GRID_COLUMNS, 10, 10));
    private final JSplitPane bodySplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
    private final HistoryChartPanel historyChart = new HistoryChartPanel();
    private final EventLogPanel eventLog = new EventLogPanel();

    private final JLabel connectionLabel = new JLabel("connecting…");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel countsLabel = new JLabel(" ");
    private final JComboBox<ApplianceChoice> deviceSelector = new JComboBox<>();
    private final JComboBox<String> windowSelector = new JComboBox<>();

    private final List<AppliancePanel> tiles = new ArrayList<>();

    /** Guards against the combo boxes firing while the view is repopulating them. */
    private boolean populating;

    /**
     * @param model      the state to render; must not be null
     * @param controller where user gestures go; must not be null
     * @throws NullPointerException if either argument is null
     */
    public DashboardView(DashboardModel model, DashboardController controller) {
        this.model = Objects.requireNonNull(model, "model");
        this.controller = Objects.requireNonNull(controller, "controller");
        build();
        model.addListener(this);
    }

    /**
     * Installs the dashboard's palette into the Swing defaults.
     *
     * <p><strong>Call this before constructing any Swing component</strong> — components read
     * these defaults once, at construction. The alternative, taking the platform look and
     * feel, produces a window that is half this palette and half the desktop's: dark tiles
     * with a white combo box and unreadable tab labels sitting between them. A dashboard
     * meant to be read at a glance cannot have two colour schemes, so the one it declares
     * wins everywhere.</p>
     *
     * <p>Only the handful of keys this window actually uses are set; everything else keeps
     * the cross-platform defaults.</p>
     */
    public static void applyTheme() {
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("Label.foreground", TEXT);

        UIManager.put("ComboBox.background", PANEL);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("ComboBox.selectionBackground", GRID);
        UIManager.put("ComboBox.selectionForeground", TEXT);
        UIManager.put("ComboBox.buttonBackground", PANEL);
        // The history controls are disabled when the dashboard runs without a database, and a
        // disabled control that keeps the platform's pale defaults is the one bright rectangle
        // on an otherwise dark window.
        UIManager.put("ComboBox.disabledBackground", BACKGROUND);
        UIManager.put("ComboBox.disabledForeground", STALE);

        UIManager.put("Button.background", PANEL);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("Button.select", GRID);
        UIManager.put("Button.disabledText", STALE);

        UIManager.put("TabbedPane.background", BACKGROUND);
        UIManager.put("TabbedPane.foreground", TEXT);
        UIManager.put("TabbedPane.selected", PANEL);
        UIManager.put("TabbedPane.contentAreaColor", PANEL);
        UIManager.put("TabbedPane.light", GRID);
        UIManager.put("TabbedPane.darkShadow", BACKGROUND);

        UIManager.put("Table.background", PANEL);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.gridColor", GRID);
        UIManager.put("TableHeader.background", BACKGROUND);
        UIManager.put("TableHeader.foreground", MUTED);

        UIManager.put("ScrollPane.background", BACKGROUND);
        UIManager.put("Viewport.background", BACKGROUND);
        UIManager.put("SplitPane.background", BACKGROUND);
        UIManager.put("SplitPaneDivider.draggingColor", GRID);

        UIManager.put("ToolTip.background", PANEL);
        UIManager.put("ToolTip.foreground", TEXT);
    }

    /** Shows the window and starts the repaint timer. */
    public void show() {
        frame.setVisible(true);
        new Timer(TICK_MS, e -> {
            tiles.forEach(JComponent::repaint);
            updateCounts();
        }).start();
    }

    /** @return the window, so the application can hook its close event. */
    public JFrame getFrame() {
        return frame;
    }

    // ---------------------------------------------------------------- construction

    private void build() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(900, 640));

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBackground(BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(BACKGROUND);

        JLabel title = new JLabel("Smart Home Energy Monitor");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        title.setForeground(TEXT);

        connectionLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        connectionLabel.setForeground(MUTED);

        header.add(title, BorderLayout.WEST);
        header.add(connectionLabel, BorderLayout.EAST);
        return header;
    }

    private JComponent buildBody() {
        applianceGrid.setBackground(BACKGROUND);
        applianceGrid.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        // GridLayout divides whatever height it is given among its rows, so on its own the
        // tiles stretch to fill the viewport and a tile ends up mostly empty space. Pinning
        // the grid to the top of a plain container leaves it at its preferred height and lets
        // the scroll pane deal with the overflow, which is what makes a tile a fixed card.
        JPanel gridHolder = new JPanel(new BorderLayout());
        gridHolder.setBackground(BACKGROUND);
        gridHolder.add(applianceGrid, BorderLayout.NORTH);

        JScrollPane grid = new JScrollPane(gridHolder);
        grid.setBorder(null);
        grid.getViewport().setBackground(BACKGROUND);
        grid.getVerticalScrollBar().setUnitIncrement(16);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(BACKGROUND);
        tabs.setForeground(TEXT);
        tabs.addTab("History", buildHistoryTab());
        tabs.addTab("Alerts", eventLog);

        bodySplit.setTopComponent(grid);
        bodySplit.setBottomComponent(tabs);
        // Extra height goes to the charts, not to the tiles: a tile is a fixed-size card and
        // stretching the space around it just pushes the history further off the screen. The
        // divider is then placed by refreshDividerLocation, because at this point the grid is
        // still empty and its preferred height is zero.
        bodySplit.setResizeWeight(0.0);
        bodySplit.setBorder(null);
        bodySplit.setBackground(BACKGROUND);
        return bodySplit;
    }

    /**
     * Sizes the tile area to exactly the rows it holds, leaving the rest to the charts.
     *
     * <p>Called whenever the tile count changes. Anything the operator does to the divider by
     * hand survives until the appliance set itself changes, which in practice is once, when
     * the catalogue loads.</p>
     */
    private void refreshDividerLocation() {
        int desired = applianceGrid.getPreferredSize().height + 8;
        int available = bodySplit.getHeight();
        if (available <= 0) {
            bodySplit.setDividerLocation(desired);
            return;
        }
        // Never take so much that the charts are squeezed out of existence.
        bodySplit.setDividerLocation(Math.min(desired, Math.max(120, available - 220)));
    }

    private JComponent buildHistoryTab() {
        JPanel tab = new JPanel(new BorderLayout(0, 6));
        tab.setBackground(PANEL);
        tab.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.LINE_AXIS));
        controls.setBackground(PANEL);

        controls.add(label("Appliance"));
        controls.add(Box.createHorizontalStrut(6));
        deviceSelector.addActionListener(e -> {
            ApplianceChoice choice = (ApplianceChoice) deviceSelector.getSelectedItem();
            if (!populating && choice != null) {
                controller.selectDevice(choice.deviceId());
            }
        });
        controls.add(deviceSelector);

        controls.add(Box.createHorizontalStrut(16));
        controls.add(label("Window"));
        controls.add(Box.createHorizontalStrut(6));
        for (Duration window : HISTORY_WINDOWS) {
            windowSelector.addItem(describe(window));
        }
        windowSelector.setSelectedItem(describe(DashboardController.DEFAULT_HISTORY_WINDOW));
        windowSelector.addActionListener(e -> {
            if (!populating) {
                controller.setHistoryWindow(HISTORY_WINDOWS.get(windowSelector.getSelectedIndex()));
            }
        });
        controls.add(windowSelector);

        controls.add(Box.createHorizontalStrut(16));
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> {
            controller.refreshHistory();
            controller.refreshEvents();
        });
        controls.add(refresh);
        controls.add(Box.createHorizontalGlue());

        tab.add(controls, BorderLayout.NORTH);
        tab.add(historyChart, BorderLayout.CENTER);

        if (!controller.hasDatabase()) {
            deviceSelector.setEnabled(false);
            windowSelector.setEnabled(false);
            refresh.setEnabled(false);
            historyChart.setEmpty("running without a database — history is unavailable");
            eventLog.showEmptyMessage("running without a database — alerts are unavailable");
        }
        return tab;
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BACKGROUND);

        statusLabel.setForeground(MUTED);
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        countsLabel.setForeground(MUTED);
        countsLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));

        bar.add(statusLabel, BorderLayout.WEST);
        bar.add(countsLabel, BorderLayout.EAST);
        return bar;
    }

    private static JLabel label(String text) {
        JLabel created = new JLabel(text);
        created.setForeground(MUTED);
        created.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        return created;
    }

    // ---------------------------------------------------------------- model callbacks

    @Override
    public void applianceUpdated(ApplianceState state) {
        for (AppliancePanel tile : tiles) {
            if (tile.getState().getDeviceId() == state.getDeviceId()) {
                tile.repaint();
                return;
            }
        }
        // A device that arrived on the feed without a tile: rebuild rather than guess.
        appliancesReset(model.getAppliances());
    }

    @Override
    public void appliancesReset(List<ApplianceState> appliances) {
        applianceGrid.removeAll();
        tiles.clear();

        Map<Integer, String> names = new LinkedHashMap<>();
        populating = true;
        try {
            ApplianceChoice selected = (ApplianceChoice) deviceSelector.getSelectedItem();
            deviceSelector.removeAllItems();
            for (ApplianceState state : appliances) {
                AppliancePanel tile = new AppliancePanel(state);
                tiles.add(tile);
                applianceGrid.add(tile);
                names.put(state.getDeviceId(), state.getName());
                deviceSelector.addItem(new ApplianceChoice(state.getDeviceId(), state.getName()));
            }
            if (selected != null) {
                deviceSelector.setSelectedItem(selected);
            }
        } finally {
            populating = false;
        }

        eventLog.setDeviceNames(names);
        applianceGrid.revalidate();
        applianceGrid.repaint();
        refreshDividerLocation();
    }

    @Override
    public void eventsChanged(List<Event> events) {
        eventLog.setEvents(events);
    }

    @Override
    public void historyChanged(int deviceId, List<Reading> readings) {
        ApplianceState state = model.getAppliance(deviceId);
        String name = state == null ? "Device " + deviceId : state.getName();
        historyChart.setSeries(name + " — power over the last "
                + describe(controller.getHistoryWindow()), readings);
    }

    @Override
    public void connectionChanged(boolean connected, String detail) {
        connectionLabel.setText(detail);
        connectionLabel.setForeground(connected ? OK : CRITICAL);
    }

    @Override
    public void statusChanged(String message) {
        statusLabel.setText(message);
    }

    private void updateCounts() {
        countsLabel.setText(model.getAppliances().size() + " appliance(s) · "
                + model.getReadingsReceived() + " reading(s) received");
    }

    private static String describe(Duration window) {
        long minutes = window.toMinutes();
        return minutes < 60 ? minutes + " min" : (minutes / 60) + " h";
    }

    /**
     * One entry of the appliance selector.
     *
     * @param deviceId the device the entry selects
     * @param name     what the operator sees
     */
    private record ApplianceChoice(int deviceId, String name) {

        @Override
        public String toString() {
            return deviceId + " · " + name;
        }
    }
}
