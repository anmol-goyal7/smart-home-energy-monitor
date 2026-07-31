package com.smarthome.energy.bench;

import com.smarthome.energy.server.AcceptStrategy;
import com.smarthome.energy.server.ClientHandler;
import com.smarthome.energy.server.ReadingDispatcher;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Evidence 1: thread-per-client against a fixed thread pool, measured rather than argued.
 *
 * <p>{@code docs/DESIGN.md} claims that thread-per-client is the right model <em>at this
 * project's scale</em>, and that the conventional objection to it — unbounded threads — buys
 * less than it looks like it does when every task blocks for the life of a connection. This
 * harness drives N synthetic meters at a fixed rate through each {@link AcceptStrategy} and
 * records sustained throughput, latency percentiles, and the peak thread count each model
 * cost.</p>
 *
 * <pre>
 *   mvn exec:java -Dexec.mainClass=com.smarthome.energy.bench.IngestBenchmark \
 *       -Dexec.args="--meters 10,50,200 --duration 60s"
 * </pre>
 *
 * <h2>What is and is not under test</h2>
 *
 * <p>The harness stands up the real {@link ClientHandler} — so every frame goes through the
 * real DFA and the real parser — behind the real {@link ReadingDispatcher}, and terminates it
 * in a counting sink rather than in MySQL. That is deliberate. The question is which accept
 * strategy serves connections better; putting a database at the end would measure the
 * database, and the two strategies would differ by whatever the insert latency happened to be
 * that afternoon. Evidence 2 measures the database, separately, for exactly that reason.</p>
 *
 * <p>Both the load generators and the server run in this one JVM and compete for the same
 * cores, which caps the absolute numbers below what the server would manage against remote
 * meters. The comparison is still sound — both strategies pay the same cost — but the
 * throughput column should be read as "under this harness", not as a capacity claim.</p>
 *
 * <p>Latency is the gap between the meter-side timestamp on the frame and the moment the sink
 * sees the parsed reading, so it spans the socket, the DFA, the parser, the dispatcher queue,
 * and the fan-out. The wire format carries that timestamp in whole milliseconds, so a p50 of
 * {@code 0} means "below the resolution the protocol itself records" rather than "instant";
 * the mean, printed under the table, is the sub-millisecond detail. Nothing about the effect
 * this benchmark looks for is near that resolution — a starved connection is late by
 * seconds.</p>
 *
 * <p>Syllabus mapping: Unit I — threading models, sockets, concurrency measurement.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class IngestBenchmark {

    /** Meter counts measured when {@code --meters} says nothing. */
    private static final int[] DEFAULT_METER_COUNTS = {10, 50, 200};

    /** Strategies compared when {@code --strategies} says nothing. */
    private static final String[] DEFAULT_STRATEGIES = {"thread-per-client", "pool:8"};

    /** Length of the measured window when {@code --duration} says nothing. */
    private static final long DEFAULT_DURATION_MILLIS = 60_000L;

    /** Unmeasured settling time before the window opens: connections, JIT, and page faults. */
    private static final long DEFAULT_WARMUP_MILLIS = 5_000L;

    /** Milliseconds between frames from one meter — the rate a real meter streams at. */
    private static final long DEFAULT_INTERVAL_MILLIS = 100L;

    /** Latency samples kept per run before further ones are counted and discarded. */
    private static final int LATENCY_CAPACITY = 4_000_000;

    /** How long to wait for every meter's TCP connection before measuring anyway. */
    private static final long CONNECT_WAIT_MILLIS = 30_000L;

    /** Pause between runs, so sockets from the previous one are out of the way. */
    private static final long SETTLE_MILLIS = 2_000L;

    private IngestBenchmark() {
        // Entry point only.
    }

    /**
     * Runs every (meter count, strategy) pair and prints the results table.
     *
     * @param args {@code --meters 10,50,200}, {@code --strategies thread-per-client,pool:8},
     *             {@code --duration 60s}, {@code --warmup 5s}, {@code --interval MS},
     *             {@code --csv PATH}, {@code --help}
     */
    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[bench] " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }
        if (options == null) {
            return;
        }

        System.out.println("[bench] ingest benchmark: meters=" + options.meterCounts
                + " strategies=" + options.strategies
                + " interval=" + options.intervalMillis + "ms"
                + " warmup=" + options.warmupMillis + "ms"
                + " window=" + options.durationMillis + "ms");
        System.out.println("[bench] each meter offers " + String.format(Locale.ROOT, "%.1f",
                1000.0 / options.intervalMillis) + " readings/s");

        List<Result> results = new ArrayList<>();
        for (int meters : options.meterCounts) {
            for (String strategy : options.strategies) {
                System.out.println("[bench] running " + meters + " meter(s) through " + strategy + " …");
                Result result = runOne(meters, strategy, options);
                results.add(result);
                System.out.println("[bench]   " + result.summary());
                sleepQuietly(SETTLE_MILLIS);
            }
        }

        System.out.println();
        printTable(results);
        if (options.csvPath != null) {
            writeCsv(options.csvPath, results);
        }
    }

    /** Stands up one ingest stack, drives it, tears it down, and returns what it measured. */
    private static Result runOne(int meterCount, String strategySpec, Options options) {
        AcceptStrategy strategy = AcceptStrategy.parse(strategySpec);
        Harness harness = new Harness(strategy);
        List<SyntheticMeter> meters = new ArrayList<>(meterCount);
        List<Thread> meterThreads = new ArrayList<>(meterCount);

        // The per-connection log lines are a synchronised write to the terminal per meter, and
        // 200 of them would both bury the table and perturb what is being timed. They are
        // silenced for the duration of the run and restored before anything is reported.
        PrintStream savedOut = System.out;
        PrintStream savedErr = System.err;

        try {
            harness.start();
            System.setOut(nullStream());
            System.setErr(nullStream());

            for (int i = 1; i <= meterCount; i++) {
                SyntheticMeter meter = new SyntheticMeter(i, "127.0.0.1", harness.port(),
                        options.intervalMillis);
                meters.add(meter);
                Thread thread = new Thread(meter, "bench-meter-" + i);
                thread.setDaemon(true);
                meterThreads.add(thread);
                thread.start();
            }

            awaitConnections(meters);
            sleepQuietly(options.warmupMillis);

            // Open the measurement window: reset the latency samples and snapshot the counters.
            LatencyRecorder window = new LatencyRecorder(LATENCY_CAPACITY);
            harness.latency = window;
            long deliveredAtStart = harness.delivered.get();
            long sentAtStart = meters.stream().mapToLong(SyntheticMeter::getSentCount).sum();
            long droppedAtStart = harness.dispatcher.stats().getDropped();
            long startNanos = System.nanoTime();

            sleepQuietly(options.durationMillis);

            long elapsedNanos = System.nanoTime() - startNanos;
            long delivered = harness.delivered.get() - deliveredAtStart;
            long sent = meters.stream().mapToLong(SyntheticMeter::getSentCount).sum() - sentAtStart;
            long dropped = harness.dispatcher.stats().getDropped() - droppedAtStart;
            double seconds = elapsedNanos / 1_000_000_000.0;
            long[] p = window.percentiles(0.50, 0.95, 0.99);

            return new Result(meterCount, strategy.name(),
                    delivered / seconds, sent / seconds,
                    p[0], p[1], p[2], window.mean(),
                    strategy.getPeakThreadCount(), harness.devicesSeen.size(),
                    delivered, dropped);

        } finally {
            meters.forEach(SyntheticMeter::stop);
            for (Thread thread : meterThreads) {
                try {
                    thread.join(TimeUnit.SECONDS.toMillis(2));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            harness.close();
            System.setOut(savedOut);
            System.setErr(savedErr);
        }
    }

    /**
     * Waits until every meter has a TCP connection, or the cap elapses.
     *
     * <p>This is the operating system's accept queue, not the strategy's: a pooled server
     * still completes the TCP handshake for a connection it has no thread to read from. The
     * wait therefore succeeds under both strategies, which is exactly the point — the meters
     * all believe they are connected, and only the latency and throughput columns reveal that
     * some of them are talking to nobody.</p>
     */
    private static void awaitConnections(List<SyntheticMeter> meters) {
        long deadline = System.currentTimeMillis() + CONNECT_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (meters.stream().allMatch(SyntheticMeter::isConnected)) {
                return;
            }
            sleepQuietly(50L);
        }
    }

    /** One ingest stack: a listener, an accept strategy, a dispatcher, and a counting sink. */
    private static final class Harness implements AutoCloseable {

        private final AcceptStrategy strategy;
        private final AtomicLong delivered = new AtomicLong();
        private final Set<Integer> devicesSeen = ConcurrentHashMap.newKeySet();
        private final CopyOnWriteArrayList<ClientHandler> handlers = new CopyOnWriteArrayList<>();
        private final AtomicInteger connectionIds = new AtomicInteger();

        private volatile LatencyRecorder latency = new LatencyRecorder(1024);
        private volatile boolean running;

        private ServerSocket listener;
        private ReadingDispatcher dispatcher;
        private Thread acceptThread;

        Harness(AcceptStrategy strategy) {
            this.strategy = strategy;
        }

        void start() {
            dispatcher = new ReadingDispatcher(List.of(ReadingDispatcher.Sink.of("bench", reading -> {
                latency.record(System.currentTimeMillis() - reading.getReadingEpochMillis());
                devicesSeen.add(reading.getDeviceId());
                delivered.incrementAndGet();
            })));
            try {
                // Port 0: let the OS pick, so repeated runs never collide with a socket the
                // previous one has not finished releasing.
                listener = new ServerSocket(0);
            } catch (IOException e) {
                throw new IllegalStateException("could not open a listening socket: " + e.getMessage(), e);
            }
            running = true;
            acceptThread = new Thread(this::acceptLoop, "bench-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        private void acceptLoop() {
            while (running) {
                Socket socket;
                try {
                    socket = listener.accept();
                } catch (IOException e) {
                    return;
                }
                int id = connectionIds.incrementAndGet();
                ClientHandler handler = new ClientHandler(socket, id, dispatcher,
                        deviceId -> true, handlers::remove);
                handlers.add(handler);
                strategy.serve(handler, id);
            }
        }

        @Override
        public void close() {
            running = false;
            try {
                listener.close();
            } catch (IOException e) {
                // Going away.
            }
            handlers.forEach(ClientHandler::shutdown);
            strategy.close();
            dispatcher.close();
        }
    }

    /** One row of the results table. */
    private record Result(int meters, String strategy, double throughput, double offered,
                          long p50, long p95, long p99, double meanLatency,
                          int peakThreads, int metersServed, long delivered, long dropped) {

        String summary() {
            return String.format(Locale.ROOT,
                    "%s: %.0f readings/s delivered (%.0f offered), p50=%dms p99=%dms, "
                            + "peak threads %d, %d/%d meters actually read",
                    strategy, throughput, offered, p50, p99, peakThreads, metersServed, meters);
        }
    }

    /** Prints the results as the markdown table the README's Evidence 1 carries. */
    private static void printTable(List<Result> results) {
        System.out.println("| Meters | Strategy | Throughput (readings/s) | p50 latency (ms) "
                + "| p99 latency (ms) | Peak threads |");
        System.out.println("| ------ | -------- | ----------------------- | ---------------- "
                + "| ---------------- | ------------ |");
        for (Result r : results) {
            System.out.printf(Locale.ROOT, "| %d | %s | %,.0f | %d | %d | %d |%n",
                    r.meters(), r.strategy(), r.throughput(), r.p50(), r.p99(), r.peakThreads());
        }

        System.out.println();
        System.out.println("Detail (offered rate, mean latency, meters actually read, drops):");
        for (Result r : results) {
            System.out.printf(Locale.ROOT,
                    "  %3d meters %-18s offered %,8.0f/s  delivered %,8.0f/s  mean %6.2f ms  "
                            + "served %3d/%-3d  dropped %d%n",
                    r.meters(), r.strategy(), r.offered(), r.throughput(), r.meanLatency(),
                    r.metersServed(), r.meters(), r.dropped());
        }
    }

    /** Writes the same rows as CSV, so the README's table is transcribed and not retyped. */
    private static void writeCsv(Path path, List<Result> results) {
        StringBuilder csv = new StringBuilder(
                "meters,strategy,throughput_per_s,offered_per_s,p50_ms,p95_ms,p99_ms,"
                        + "mean_ms,peak_threads,meters_served,delivered,dropped\n");
        for (Result r : results) {
            csv.append(String.format(Locale.ROOT, "%d,%s,%.1f,%.1f,%d,%d,%d,%.3f,%d,%d,%d,%d%n",
                    r.meters(), r.strategy(), r.throughput(), r.offered(), r.p50(), r.p95(),
                    r.p99(), r.meanLatency(), r.peakThreads(), r.metersServed(), r.delivered(),
                    r.dropped()));
        }
        try {
            Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
            System.out.println("[bench] wrote " + results.size() + " row(s) to " + path);
        } catch (IOException e) {
            System.err.println("[bench] could not write " + path + ": " + e.getMessage());
        }
    }

    /** A stream that discards everything, used to silence the server during a timed run. */
    private static PrintStream nullStream() {
        return new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Parsed command line. */
    private record Options(List<Integer> meterCounts, List<String> strategies, long durationMillis,
                           long warmupMillis, long intervalMillis, Path csvPath) {

        /** @return the options, or null if {@code --help} was asked for and printed. */
        static Options parse(String[] args) {
            List<Integer> meters = new ArrayList<>();
            List<String> strategies = new ArrayList<>();
            long duration = DEFAULT_DURATION_MILLIS;
            long warmup = DEFAULT_WARMUP_MILLIS;
            long interval = DEFAULT_INTERVAL_MILLIS;
            Path csv = null;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--meters" -> meters.addAll(parseCounts(value(args, ++i)));
                    case "--strategies" -> strategies.addAll(parseStrategies(value(args, ++i)));
                    case "--duration" -> duration = parseDuration(value(args, ++i));
                    case "--warmup" -> warmup = parseDuration(value(args, ++i));
                    case "--interval" -> interval = parseLong(value(args, ++i), "--interval");
                    case "--csv" -> csv = Path.of(value(args, ++i));
                    case "--help", "-h" -> {
                        printUsage();
                        return null;
                    }
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }

            if (meters.isEmpty()) {
                Arrays.stream(DEFAULT_METER_COUNTS).forEach(meters::add);
            }
            if (strategies.isEmpty()) {
                strategies.addAll(List.of(DEFAULT_STRATEGIES));
            }
            if (interval <= 0) {
                throw new IllegalArgumentException("--interval must be positive, was " + interval);
            }
            if (duration <= 0) {
                throw new IllegalArgumentException("--duration must be positive");
            }
            return new Options(List.copyOf(meters), List.copyOf(strategies), duration, warmup,
                    interval, csv);
        }

        private static List<Integer> parseCounts(String raw) {
            List<Integer> counts = new ArrayList<>();
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int count = (int) parseLong(trimmed, "--meters");
                if (count <= 0) {
                    throw new IllegalArgumentException("--meters values must be positive, got " + count);
                }
                counts.add(count);
            }
            if (counts.isEmpty()) {
                throw new IllegalArgumentException("--meters needs at least one count");
            }
            return counts;
        }

        private static List<String> parseStrategies(String raw) {
            List<String> specs = new ArrayList<>();
            for (String part : raw.split(",(?![0-9])")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                // Fail here rather than half way through a twenty-minute run.
                AcceptStrategy.parse(trimmed).close();
                specs.add(trimmed);
            }
            if (specs.isEmpty()) {
                throw new IllegalArgumentException("--strategies needs at least one strategy");
            }
            return specs;
        }

        /** Accepts {@code 60s}, {@code 500ms}, or a bare number of seconds. */
        private static long parseDuration(String raw) {
            String trimmed = raw.trim().toLowerCase(Locale.ROOT);
            if (trimmed.endsWith("ms")) {
                return parseLong(trimmed.substring(0, trimmed.length() - 2), "duration");
            }
            if (trimmed.endsWith("s")) {
                return parseLong(trimmed.substring(0, trimmed.length() - 1), "duration") * 1000L;
            }
            return parseLong(trimmed, "duration") * 1000L;
        }

        private static long parseLong(String raw, String option) {
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " needs a whole number, got '" + raw + "'", e);
            }
        }

        private static String value(String[] args, int index) {
            if (index >= args.length) {
                throw new IllegalArgumentException("option " + args[index - 1] + " needs a value");
            }
            return args[index];
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage: IngestBenchmark [options]

                  --meters 10,50,200        meter counts to measure (default 10,50,200)
                  --strategies A,B          accept strategies to compare
                                            (default thread-per-client,pool:8)
                  --duration 60s            length of each measured window (default 60s)
                  --warmup 5s               unmeasured settling time before each window
                  --interval MS             milliseconds between frames from one meter
                                            (default 100, i.e. 10 readings/s per meter)
                  --csv PATH                also write the results as CSV
                  --help                    show this message
                """);
    }
}
