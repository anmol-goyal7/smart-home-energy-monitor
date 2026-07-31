package com.smarthome.energy.server;

import com.smarthome.energy.db.ConnectionFactory;
import com.smarthome.energy.db.DataAccessException;
import com.smarthome.energy.db.DeviceDao;
import com.smarthome.energy.db.ThresholdDao;
import com.smarthome.energy.model.Device;
import com.smarthome.energy.protocol.MeterMessage;
import com.smarthome.energy.rules.RuleContext;
import com.smarthome.energy.rules.RuleEngine;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

/**
 * Application entry point for the ingest server and the owner of the accept loop.
 *
 * <p>Opens a {@code ServerSocket} on the configured meter port and blocks in an accept
 * loop. For every meter connection it accepts, it spawns a dedicated {@link ClientHandler}
 * thread (the thread-per-client model — see {@code docs/DESIGN.md}). It also stands up the
 * shared collaborators used by every handler: the {@link ReadingDispatcher}, the JDBC
 * DAOs, the {@code RuleEngine}, and the {@link DashboardPublisher}.</p>
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>load {@link ServerConfig} and initialise shared singletons;</li>
 *   <li>accept meter connections and hand each to a new handler thread;</li>
 *   <li>coordinate a clean shutdown (stop accepting, drain handlers, close the pool).</li>
 * </ul>
 *
 * <h2>Running without a database</h2>
 *
 * <p>{@code --no-persistence} starts the server with the dashboard feed as its only sink.
 * The live pipeline — sockets, threads, DFA, parser, dispatcher, publisher — is then
 * exercisable on a machine that has no MySQL, which is how this phase was developed and
 * tested and what makes the networking path debuggable without dragging the storage layer
 * into every experiment. It is a diagnostic mode, not a supported way to run the system:
 * readings are not kept, so history, alerts, and the analytics have nothing to read.</p>
 *
 * <p>With persistence on, the device catalogue is loaded at start-up and a reading naming an
 * unknown device is refused by the handler. Without that check the reading would reach the
 * insert and be rejected by the foreign key — as a {@code DataAccessException} raised on a
 * dispatcher worker, several hand-offs away from the connection that caused it.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals, TCP sockets and multithreading.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class EnergyMonitorServer implements AutoCloseable {

    /** Seconds between the one-line status summaries printed while the server runs. */
    private static final long STATUS_INTERVAL_SECONDS = 10L;

    private final ServerConfig config;
    private final boolean persistenceEnabled;
    private final AcceptStrategy acceptStrategy;

    private final CopyOnWriteArrayList<ClientHandler> handlers = new CopyOnWriteArrayList<>();
    private final AtomicInteger connectionCounter = new AtomicInteger();

    private volatile boolean running;
    private ServerSocket listener;
    private DashboardPublisher publisher;
    private ReadingDispatcher dispatcher;
    private RuleEngine engine;
    private Thread statusThread;

    /**
     * Creates a server using the shipped thread-per-client model.
     *
     * @param config             ports and timings; must not be null
     * @param persistenceEnabled whether readings are written to MySQL
     * @throws NullPointerException if {@code config} is null
     */
    public EnergyMonitorServer(ServerConfig config, boolean persistenceEnabled) {
        this(config, persistenceEnabled, AcceptStrategy.threadPerClient());
    }

    /**
     * @param config             ports and timings; must not be null
     * @param persistenceEnabled whether readings are written to MySQL
     * @param acceptStrategy     how an accepted connection is given a thread; must not be null
     * @throws NullPointerException if {@code config} or {@code acceptStrategy} is null
     */
    public EnergyMonitorServer(ServerConfig config, boolean persistenceEnabled,
                               AcceptStrategy acceptStrategy) {
        this.config = Objects.requireNonNull(config, "config");
        this.persistenceEnabled = persistenceEnabled;
        this.acceptStrategy = Objects.requireNonNull(acceptStrategy, "acceptStrategy");
    }

    /**
     * Starts the server: opens both ports, wires up the sinks, and begins accepting.
     *
     * @throws IOException         if either port cannot be bound
     * @throws DataAccessException if persistence is enabled and the database is unreachable
     *                             or misconfigured
     */
    public void start() throws IOException {
        List<ReadingDispatcher.Sink> sinks = new ArrayList<>();
        IntPredicate knownDevice = deviceId -> true;

        publisher = new DashboardPublisher(config.getDashboardPort());

        // The dashboard is registered first on purpose: it is the latency-sensitive consumer.
        sinks.add(ReadingDispatcher.Sink.of("dashboard", publisher::publish));

        if (persistenceEnabled) {
            ConnectionFactory connections = config.connectionFactory();
            System.out.println("[server] persistence enabled: " + connections.getUrl());

            List<Device> catalogue = loadCatalogue(new DeviceDao(connections));
            Set<Integer> ids = new LinkedHashSet<>();
            catalogue.forEach(device -> ids.add(device.getDeviceId()));
            knownDevice = ids::contains;

            // Detection reads its limits from the same database, so it comes up with
            // persistence and not without it: with no thresholds table there is nothing to
            // decide what "normal" is, and a rule engine that invents its own limits would
            // raise alerts against numbers nobody configured.
            ThresholdDao thresholdDao = new ThresholdDao(connections);
            RuleContext thresholds = new RuleContext(catalogue, thresholdDao.findAll());
            engine = new RuleEngine(thresholds);
            System.out.println("[server] detection enabled: " + thresholds);

            sinks.add(new PersistenceSink(connections, engine, publisher::publishAlert));

            // The dashboard's threshold editor writes to the same table this context was built
            // from, and the server has no other way of hearing about it.
            publisher.setCommandHandler(command -> handleCommand(command, catalogue, thresholdDao));
        } else {
            System.out.println("[server] persistence DISABLED (--no-persistence): readings are "
                    + "broadcast to dashboards but not stored, and no thresholds are loaded, so "
                    + "nothing is evaluated for alerts");
        }

        dispatcher = new ReadingDispatcher(sinks);
        publisher.start();

        listener = new ServerSocket(config.getMeterPort());
        running = true;
        System.out.println("[server] meter ingest listening on port " + config.getMeterPort());
        System.out.println("[server] accept strategy: " + acceptStrategy.name());
        System.out.println("[server] sinks: " + String.join(", ", dispatcher.sinkNames()));

        statusThread = new Thread(this::reportStatus, "server-status");
        statusThread.setDaemon(true);
        statusThread.start();

        acceptLoop(knownDevice);
    }

    /**
     * Stops accepting, disconnects the meters, drains the dispatcher, and closes the feed.
     *
     * <p>The order matters. Closing the listener first stops new work arriving; closing the
     * handlers next means no more readings are queued; draining the dispatcher after that
     * lets everything already accepted reach the database before the process exits. Doing it
     * the other way round would throw away readings the meters had already handed over.</p>
     */
    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        System.out.println("[server] shutting down");

        closeQuietly(listener);
        for (ClientHandler handler : handlers) {
            handler.shutdown();
        }
        acceptStrategy.close();
        if (dispatcher != null) {
            dispatcher.close();
            System.out.println("[server] dispatcher drained — " + dispatcher.stats());
        }
        if (publisher != null) {
            publisher.close();
        }
        if (statusThread != null) {
            statusThread.interrupt();
        }
    }

    /** @return the number of meters currently connected. */
    public int getConnectedMeterCount() {
        return handlers.size();
    }

    /** @return the strategy deciding how accepted connections are given threads. */
    public AcceptStrategy getAcceptStrategy() {
        return acceptStrategy;
    }

    /** Accepts meter connections until the server is closed, one handler thread each. */
    private void acceptLoop(IntPredicate knownDevice) {
        while (running) {
            Socket socket;
            try {
                socket = listener.accept();
            } catch (IOException e) {
                if (running) {
                    System.err.println("[server] accept failed: " + e.getMessage());
                }
                return;
            }

            int id = connectionCounter.incrementAndGet();
            ClientHandler handler = new ClientHandler(socket, id, dispatcher, knownDevice, handlers::remove);
            handlers.add(handler);

            // The whole concurrency model of the ingest path is this line — which is why it
            // is a pluggable strategy rather than a `new Thread(...)`, so the two candidate
            // models can be run against each other (Evidence 1) instead of argued about.
            if (!acceptStrategy.serve(handler, id)) {
                handlers.remove(handler);
                handler.shutdown();
            }
        }
    }

    /**
     * Carries out a command a subscribed dashboard sent up the live feed.
     *
     * <p>{@code RELOAD} re-reads the {@code thresholds} table and swaps the result into the
     * engine. Building a whole new {@link RuleContext} rather than editing the current one is
     * the point: the context is replaced wholesale and read through a {@code volatile} field,
     * so a worker part-way through evaluating a reading sees the old limits or the new ones
     * and never a mixture of both.</p>
     *
     * <p>The device catalogue is the one loaded at start-up. A threshold edit cannot add a
     * device, and re-reading {@code devices} here would make this command quietly do two
     * things.</p>
     */
    private String handleCommand(String command, List<Device> catalogue, ThresholdDao thresholds) {
        if (!MeterMessage.RELOAD_COMMAND.equals(command)) {
            return MeterMessage.ERROR_PREFIX + "unknown command '" + command + "'; this server "
                    + "understands " + MeterMessage.RELOAD_COMMAND;
        }
        if (engine == null) {
            return MeterMessage.ERROR_PREFIX + "detection is not running, so there are no "
                    + "thresholds to reload";
        }
        try {
            engine.reload(new RuleContext(catalogue, thresholds.findAll()));
            return MeterMessage.RELOAD_ACK;
        } catch (DataAccessException e) {
            return MeterMessage.ERROR_PREFIX + "could not re-read the thresholds: " + e.getMessage();
        }
    }

    /**
     * Reads the device catalogue, which serves two purposes: refusing unknown device ids at
     * the door, and naming devices in the alerts the rule engine raises.
     */
    private static List<Device> loadCatalogue(DeviceDao devices) {
        List<Device> catalogue = devices.findAll();
        if (catalogue.isEmpty()) {
            System.err.println("[server] the devices table is empty — every reading will be "
                    + "refused. Load sql/seed.sql.");
        }
        for (Device device : catalogue) {
            System.out.println("[server]   device " + device.getDeviceId() + ": " + device.getName()
                    + " (" + device.getRatedPowerWatts() + " W rated)");
        }
        return catalogue;
    }

    /** Prints one status line every {@link #STATUS_INTERVAL_SECONDS} so the run is observable. */
    private void reportStatus() {
        long previousDelivered = 0;
        while (running) {
            try {
                TimeUnit.SECONDS.sleep(STATUS_INTERVAL_SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) {
                return;
            }
            ReadingDispatcher.Stats stats = dispatcher.stats();
            double rate = (stats.getDelivered() - previousDelivered) / (double) STATUS_INTERVAL_SECONDS;
            previousDelivered = stats.getDelivered();
            String alerts = engine == null ? "" : " alerts=" + engine.getRaisedCount();
            System.out.printf("[server] meters=%d threads=%d/%d subscribers=%d %s%s rate=%.1f/s%n",
                    handlers.size(), acceptStrategy.getActiveThreadCount(),
                    acceptStrategy.getPeakThreadCount(), publisher.getSubscriberCount(),
                    stats, alerts, rate);
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException e) {
            // Shutting down; nothing to recover.
        }
    }

    /**
     * Starts the ingest server and blocks until the process is interrupted.
     *
     * @param args {@code --meter-port N}, {@code --dashboard-port N}, {@code --no-persistence},
     *             {@code --accept STRATEGY}, {@code --help}
     */
    public static void main(String[] args) {
        ServerConfig config;
        boolean persistence = true;
        AcceptStrategy strategy = AcceptStrategy.threadPerClient();

        try {
            config = ServerConfig.load();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--meter-port" -> config = config.withMeterPort(intArg(args, ++i));
                    case "--dashboard-port" -> config = config.withDashboardPort(intArg(args, ++i));
                    case "--no-persistence" -> persistence = false;
                    case "--accept" -> strategy = AcceptStrategy.parse(stringArg(args, ++i));
                    case "--help", "-h" -> {
                        printUsage();
                        return;
                    }
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("[server] " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        System.out.println("[server] starting with " + config);
        EnergyMonitorServer server = new EnergyMonitorServer(config, persistence, strategy);

        // Ctrl-C arrives here, which is what makes the shutdown ordering in close() worth
        // having: the normal way this process ends is a signal, not a return from main.
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "server-shutdown"));

        try {
            server.start();
        } catch (IOException e) {
            System.err.println("[server] could not start: " + e.getMessage());
            System.exit(1);
        } catch (DataAccessException e) {
            System.err.println("[server] database unavailable: " + e.getMessage());
            System.err.println("[server] start MySQL (docker compose up -d), or run with "
                    + "--no-persistence to bring up the live pipeline alone.");
            System.exit(1);
        }
    }

    private static String stringArg(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException("option " + args[index - 1] + " needs a value");
        }
        return args[index];
    }

    private static int intArg(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException("option " + args[index - 1] + " needs a value");
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("option " + args[index - 1] + " needs a number, got '"
                    + args[index] + "'", e);
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage: EnergyMonitorServer [options]

                  --meter-port N       port the meter simulators connect to (default from db.properties)
                  --dashboard-port N   port the dashboard subscribes to (default from db.properties)
                  --no-persistence     run the live pipeline without MySQL; readings are broadcast
                                       to dashboards but not stored
                  --accept STRATEGY    how accepted connections get a thread: thread-per-client
                                       (default), pool, or pool:N. See Evidence 1 in the README
                                       before choosing anything but the default.
                  --help               show this message
                """);
    }
}
