package com.smarthome.energy.server;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How an accepted connection becomes a running {@link ClientHandler}.
 *
 * <p>This is the seam the concurrency-model argument in {@code docs/DESIGN.md} turns on.
 * {@link EnergyMonitorServer}'s accept loop does not decide how a connection is served; it
 * accepts a socket, builds a handler, and hands it here. Making that one line pluggable is
 * what lets the same server be run both ways and measured, rather than argued about — see
 * {@code bench.IngestBenchmark} and Evidence 1 in the README.</p>
 *
 * <p>Two implementations are provided, and the difference between them is the whole
 * comparison:</p>
 *
 * <ul>
 *   <li>{@link #threadPerClient()} — one dedicated platform thread per connection, started at
 *       accept time and living exactly as long as the connection does. The model the system
 *       ships with.</li>
 *   <li>{@link #fixedPool(int)} — a fixed pool of {@code n} threads that connections are
 *       submitted to. The conventional "don't spawn unbounded threads" answer.</li>
 * </ul>
 *
 * <h2>Why the pool is not the safe default it sounds like</h2>
 *
 * <p>A thread pool is the right shape when tasks are short: many small units of work share
 * few threads because each releases its thread quickly. A {@code ClientHandler} is the
 * opposite kind of task — it blocks on a socket read for the entire life of the connection,
 * so submitting it to a pool of {@code n} threads does not multiplex {@code n} threads across
 * many meters, it means meter {@code n+1} onwards sits in the queue, connected but never
 * read from, until one of the first {@code n} disconnects. The pool bounds the thread count
 * by bounding the number of meters that are served at all.</p>
 *
 * <p>That is not an argument against pools; it is an argument that a pool belongs where the
 * work is bounded in duration. This system already has one in the right place — the
 * {@link ReadingDispatcher}'s workers, whose task is "deliver one reading" and returns. The
 * benchmark exists to put a number on the difference rather than leave it as this
 * paragraph.</p>
 *
 * <p>Implementations must be safe to call from the accept loop, which is a single thread, and
 * from {@link #close()}, which is not.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals, threading models, executors.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public interface AcceptStrategy extends AutoCloseable {

    /** @return the short name used in logs, benchmark tables, and the {@code --accept} option. */
    String name();

    /**
     * Puts a freshly accepted connection's handler into service.
     *
     * @param handler      the handler for the accepted socket; must not be null
     * @param connectionId the accept-order sequence number, used to name the thread
     * @return true if the handler was started or queued, false if the strategy is shutting
     *         down and the connection must be dropped
     * @throws NullPointerException if {@code handler} is null
     */
    boolean serve(ClientHandler handler, int connectionId);

    /**
     * The high-water mark of threads this strategy has had running handlers at once.
     *
     * <p>Reported by the ingest benchmark, because "how many threads did that cost" is half
     * of what the concurrency-model choice is about — the other half being throughput.</p>
     *
     * @return the peak count of concurrently live handler threads
     */
    int getPeakThreadCount();

    /** @return handler threads currently alive. */
    int getActiveThreadCount();

    /**
     * Stops serving and releases the strategy's threads.
     *
     * <p>Called after the listener is closed and every handler has been told to shut down, so
     * there is nothing left to wait for but the threads themselves noticing.</p>
     */
    @Override
    void close();

    /**
     * The shipped model: a new daemon thread per connection.
     *
     * @return a thread-per-client strategy
     */
    static AcceptStrategy threadPerClient() {
        return new ThreadPerClientStrategy();
    }

    /**
     * A fixed thread pool that connections are submitted to.
     *
     * @param workers pool size; must be positive
     * @return a pooled strategy
     * @throws IllegalArgumentException if {@code workers} is not positive
     */
    static AcceptStrategy fixedPool(int workers) {
        return new ThreadPoolStrategy(workers);
    }

    /**
     * Parses the {@code --accept} option.
     *
     * @param spec {@code thread-per-client}, {@code pool} (eight workers), or {@code pool:N}
     * @return the named strategy
     * @throws IllegalArgumentException if the spec names no known strategy or carries an
     *                                  unparseable worker count
     */
    static AcceptStrategy parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("--accept needs a strategy name");
        }
        String trimmed = spec.trim();
        if (trimmed.equals("thread-per-client")) {
            return threadPerClient();
        }
        if (trimmed.equals("pool")) {
            return fixedPool(ThreadPoolStrategy.DEFAULT_WORKERS);
        }
        if (trimmed.startsWith("pool:")) {
            String size = trimmed.substring("pool:".length());
            try {
                return fixedPool(Integer.parseInt(size));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--accept pool size must be a whole number, got '"
                        + size + "'", e);
            }
        }
        throw new IllegalArgumentException("unknown accept strategy '" + spec
                + "'; expected thread-per-client, pool, or pool:N");
    }
}

/**
 * One dedicated thread per connection, started at accept time.
 *
 * <p>Threads are daemons so a stuck meter cannot keep the JVM alive past shutdown; the
 * server closes each handler's socket to unblock the read, and the thread then ends on its
 * own.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
final class ThreadPerClientStrategy implements AcceptStrategy {

    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();

    private volatile boolean running = true;

    @Override
    public String name() {
        return "thread-per-client";
    }

    @Override
    public boolean serve(ClientHandler handler, int connectionId) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        if (!running) {
            return false;
        }
        recordStart();
        Thread thread = new Thread(() -> {
            try {
                handler.run();
            } finally {
                active.decrementAndGet();
            }
        }, "conn-" + connectionId);
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    @Override
    public int getPeakThreadCount() {
        return peak.get();
    }

    @Override
    public int getActiveThreadCount() {
        return active.get();
    }

    @Override
    public void close() {
        // Nothing to shut down: the threads are daemons and end when their sockets close.
        running = false;
    }

    /** Bumps the live count and raises the high-water mark if this connection set a new one. */
    private void recordStart() {
        int live = active.incrementAndGet();
        peak.accumulateAndGet(live, Math::max);
    }

    @Override
    public String toString() {
        return "AcceptStrategy[thread-per-client, active=" + active.get() + ", peak=" + peak.get() + "]";
    }
}

/**
 * A fixed pool of threads that accepted connections are submitted to.
 *
 * <p>The queue is unbounded, which is deliberate and is the behaviour the benchmark is meant
 * to expose: connections beyond the pool size are accepted by the operating system and then
 * wait, so from the meter's point of view the server is reachable but silent. A bounded queue
 * would convert that into a refused connection, which is arguably better behaviour but would
 * hide the effect being measured.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
final class ThreadPoolStrategy implements AcceptStrategy {

    /** Pool size used when {@code --accept pool} names no number. */
    static final int DEFAULT_WORKERS = 8;

    /** How long {@link #close()} waits for in-flight handlers before giving up on them. */
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;

    private final int workers;
    private final ThreadPoolExecutor pool;
    private final AtomicInteger active = new AtomicInteger();

    ThreadPoolStrategy(int workers) {
        if (workers <= 0) {
            throw new IllegalArgumentException("pool size must be positive, was " + workers);
        }
        this.workers = workers;
        ThreadFactory factory = runnable -> {
            Thread thread = Executors.defaultThreadFactory().newThread(runnable);
            thread.setDaemon(true);
            return thread;
        };
        this.pool = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), factory);
    }

    @Override
    public String name() {
        return "pool(" + workers + ")";
    }

    @Override
    public boolean serve(ClientHandler handler, int connectionId) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        try {
            pool.execute(() -> {
                active.incrementAndGet();
                try {
                    Thread.currentThread().setName("conn-" + connectionId);
                    handler.run();
                } finally {
                    active.decrementAndGet();
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            // Only reachable after close(): the queue is unbounded.
            return false;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>For a fixed pool this can never exceed the pool size, which is the point: the
     * benchmark's "peak threads" column reads {@code 8} at every meter count, and the
     * throughput column next to it is what that bound cost.</p>
     */
    @Override
    public int getPeakThreadCount() {
        return pool.getLargestPoolSize();
    }

    @Override
    public int getActiveThreadCount() {
        return active.get();
    }

    /** @return connections accepted but not yet given a thread. */
    public int getQueuedConnectionCount() {
        return pool.getQueue().size();
    }

    @Override
    public void close() {
        pool.shutdownNow();
        try {
            pool.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return "AcceptStrategy[" + name() + ", active=" + active.get()
                + ", queued=" + getQueuedConnectionCount() + "]";
    }
}
