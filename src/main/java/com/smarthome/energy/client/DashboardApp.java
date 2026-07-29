package com.smarthome.energy.client;

import com.smarthome.energy.client.controller.DashboardController;
import com.smarthome.energy.client.model.DashboardModel;
import com.smarthome.energy.client.view.DashboardView;
import com.smarthome.energy.db.DataAccessException;
import com.smarthome.energy.server.ServerConfig;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Entry point for the Swing dashboard application.
 *
 * <p>Bootstraps the Model-View-Controller triad: constructs the {@link DashboardModel},
 * builds the {@link DashboardView} on the Swing event dispatch thread, and wires them
 * together through the {@link DashboardController}. Also starts the {@link LiveFeedClient}
 * so live readings begin flowing into the model as soon as the window opens.</p>
 *
 * <p>The whole of the UI is constructed inside {@code SwingUtilities.invokeLater}. Building
 * a {@code JFrame} on the main thread usually appears to work, which is exactly what makes it
 * worth being strict about: the failure is a race that shows up on a different machine, under
 * a different look and feel, once.</p>
 *
 * <p>If the database cannot be reached the dashboard still opens, on the live feed alone, and
 * says so in its status bar. The alternative — refusing to start — would mean the operator
 * loses the live view precisely when something has gone wrong with the back end, which is
 * when they need it most.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming with Swing/AWT (application bootstrap,
 * EDT usage).</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
public final class DashboardApp {

    private DashboardApp() {
        // Entry point only.
    }

    /**
     * Opens the dashboard.
     *
     * @param args {@code --host H}, {@code --port N}, {@code --no-db}, {@code --help}
     */
    public static void main(String[] args) {
        ServerConfig config;
        String host = "localhost";
        boolean useDatabase = true;

        try {
            config = ServerConfig.load();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = stringArg(args, ++i);
                    case "--port" -> config = config.withDashboardPort(intArg(args, ++i));
                    case "--no-db" -> useDatabase = false;
                    case "--help", "-h" -> {
                        printUsage();
                        return;
                    }
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("[dashboard] " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("[dashboard] no display available; the Swing dashboard needs one. "
                    + "The server and simulators run headless.");
            System.exit(1);
            return;
        }

        HistoryQueryService history = useDatabase ? openDatabase() : null;
        String feedHost = host;
        int feedPort = config.getDashboardPort();

        SwingUtilities.invokeLater(() -> start(feedHost, feedPort, history));
    }

    /** Builds the MVC triad and starts the feed. Runs on the event dispatch thread. */
    private static void start(String host, int port, HistoryQueryService history) {
        DashboardView.applyTheme();

        DashboardModel model = new DashboardModel();
        DashboardController controller = history == null
                ? new DashboardController(model)
                : new DashboardController(model, history);

        DashboardView view = new DashboardView(model, controller);
        LiveFeedClient feed = new LiveFeedClient(host, port, controller);

        view.getFrame().addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                feed.stop();
            }
        });

        view.show();
        model.setStatus(history == null
                ? "live feed only (--no-db): history and alerts are unavailable"
                : "reading history from " + history.getUrl());

        // The catalogue and the alert log are one-off reads; the tiles fill in from the feed.
        controller.loadCatalogue();
        controller.refreshEvents();
        feed.start();
    }

    /** Opens the database, downgrading to a live-feed-only dashboard rather than failing. */
    private static HistoryQueryService openDatabase() {
        try {
            return HistoryQueryService.fromDefaultConfig();
        } catch (DataAccessException e) {
            System.err.println("[dashboard] no database (" + e.getMessage() + ")");
            System.err.println("[dashboard] opening on the live feed alone; history and alerts "
                    + "will be empty.");
            return null;
        }
    }

    private static String stringArg(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException("option " + args[index - 1] + " needs a value");
        }
        return args[index];
    }

    private static int intArg(String[] args, int index) {
        try {
            return Integer.parseInt(stringArg(args, index));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("option " + args[index - 1] + " needs a number, got '"
                    + args[index] + "'", e);
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage: DashboardApp [options]

                  --host H     server host (default localhost)
                  --port N     server live-feed port (default from db.properties)
                  --no-db      skip JDBC entirely: live tiles only, no history or alerts
                  --help       show this message
                """);
    }
}
