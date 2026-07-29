package com.smarthome.energy.server;

import com.smarthome.energy.model.Reading;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Fan-out point that routes every validated {@code Reading} to its consumers.
 *
 * <p>This is the seam that keeps ingest fast and decoupled. A {@link ClientHandler} does
 * not know about the database, the rule engine, or the dashboard; it only calls
 * {@link #dispatch(Reading)}. The dispatcher is responsible for:
 * <ol>
 *   <li>publishing the reading to live dashboard subscribers via {@link DashboardPublisher};</li>
 *   <li>persisting it through {@code ReadingDao};</li>
 *   <li>submitting it to the {@code RuleEngine} for power-quality evaluation
 *       (off the socket read path — see {@code docs/DESIGN.md}).</li>
 * </ol>
 *
 * <p>Keeping the rule evaluation off the handler's read loop means a burst of anomalies
 * cannot slow down message ingestion from the meters. The mechanism is a bounded queue with
 * its own worker threads: {@link #dispatch(Reading)} only enqueues, so the handler returns to
 * its socket immediately no matter how slow a consumer is.</p>
 *
 * <h2>Three decisions worth defending</h2>
 *
 * <p><strong>The queue is bounded and overflow drops.</strong> An unbounded queue in front of
 * a stalled database does not preserve readings, it converts a visible problem into a heap
 * exhaustion twenty minutes later. A bounded queue that drops the newest arrival and counts
 * it keeps the server alive and makes the loss measurable — {@link Stats#getDropped()} is
 * reported by the server's status line, so "we lost readings" is something the operator can
 * see rather than infer.</p>
 *
 * <p><strong>Consumer order puts the dashboard first.</strong> The live feed is the
 * latency-sensitive consumer — an operator watching a tile notices a delay of half a second,
 * while the database only has to be right eventually. Publishing before persisting keeps UI
 * latency independent of insert latency.</p>
 *
 * <p><strong>A failing consumer is isolated, not fatal.</strong> Each sink is invoked inside
 * its own try/catch: a database that has gone away must not stop the dashboard updating, and
 * neither must stop the socket reads. Failures are counted and logged with a throttle, since
 * a dead database produces one failure per reading per second and an unthrottled log would
 * bury everything else.</p>
 *
 * <p>Worker count defaults to more than one, so a slow insert does not serialise the whole
 * fan-out. Readings can therefore be persisted out of order — which does not matter, because
 * every reading carries its own {@code reading_ts} and every query orders by it rather than
 * by insertion order.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals, concurrency (producer/consumer).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class ReadingDispatcher implements AutoCloseable {

    /** Readings held between the handlers and the workers before overflow starts dropping. */
    public static final int DEFAULT_QUEUE_CAPACITY = 10_000;

    /** Threads draining the queue into the consumers. */
    public static final int DEFAULT_WORKERS = 2;

    /** How long a worker waits on an empty queue before re-checking whether it should stop. */
    private static final long POLL_TIMEOUT_MS = 200L;

    /** Minimum gap between two logged failures from the same sink. */
    private static final long FAILURE_LOG_INTERVAL_MS = 5_000L;

    /**
     * One destination for readings, named so a failure can say which one broke.
     *
     * <p>A named interface rather than a bare {@link Consumer} because the name is what turns
     * "a consumer threw" into "the persistence sink threw", which is the difference between a
     * log line worth reading and one worth ignoring.</p>
     */
    public interface Sink {

        /** @return the short name used in log messages and statistics. */
        String name();

        /**
         * Handles one reading.
         *
         * @param reading the reading to consume; never null
         */
        void accept(Reading reading);

        /**
         * Wraps a lambda as a named sink.
         *
         * @param name     short name for diagnostics; must not be null
         * @param delegate what to do with each reading; must not be null
         * @return the sink
         * @throws NullPointerException if either argument is null
         */
        static Sink of(String name, Consumer<Reading> delegate) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(delegate, "delegate");
            return new Sink() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public void accept(Reading reading) {
                    delegate.accept(reading);
                }

                @Override
                public String toString() {
                    return "Sink[" + name + "]";
                }
            };
        }
    }

    private final List<Sink> sinks;
    private final List<AtomicLong> failureCounts;
    private final List<AtomicLong> lastFailureLogMillis;
    private final BlockingQueue<Reading> queue;
    private final List<Thread> workers = new ArrayList<>();

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();

    private volatile boolean running = true;

    /**
     * Creates a dispatcher with the default queue capacity and worker count.
     *
     * @param sinks the consumers, in the order they should be invoked; must not be null or
     *              empty
     * @throws NullPointerException     if {@code sinks} is null
     * @throws IllegalArgumentException if {@code sinks} is empty
     */
    public ReadingDispatcher(List<Sink> sinks) {
        this(sinks, DEFAULT_QUEUE_CAPACITY, DEFAULT_WORKERS);
    }

    /**
     * Creates a dispatcher and starts its workers.
     *
     * @param sinks         the consumers, in the order they should be invoked; must not be
     *                      null or empty
     * @param queueCapacity readings buffered before overflow starts dropping; must be positive
     * @param workerCount   threads draining the queue; must be positive
     * @throws NullPointerException     if {@code sinks} is null
     * @throws IllegalArgumentException if {@code sinks} is empty or a size is not positive
     */
    public ReadingDispatcher(List<Sink> sinks, int queueCapacity, int workerCount) {
        Objects.requireNonNull(sinks, "sinks");
        if (sinks.isEmpty()) {
            throw new IllegalArgumentException("a dispatcher with no sinks would discard every reading");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive, was " + queueCapacity);
        }
        if (workerCount <= 0) {
            throw new IllegalArgumentException("workerCount must be positive, was " + workerCount);
        }

        this.sinks = List.copyOf(sinks);
        this.failureCounts = new ArrayList<>(this.sinks.size());
        this.lastFailureLogMillis = new ArrayList<>(this.sinks.size());
        for (int i = 0; i < this.sinks.size(); i++) {
            failureCounts.add(new AtomicLong());
            lastFailureLogMillis.add(new AtomicLong());
        }
        this.queue = new ArrayBlockingQueue<>(queueCapacity);

        for (int i = 1; i <= workerCount; i++) {
            Thread worker = new Thread(this::drain, "dispatch-" + i);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
    }

    /**
     * Hands a reading to the consumers, without waiting for any of them.
     *
     * @param reading the validated reading; must not be null
     * @return true if the reading was queued, false if the queue was full and it was dropped
     * @throws NullPointerException if {@code reading} is null
     */
    public boolean dispatch(Reading reading) {
        Objects.requireNonNull(reading, "reading");
        if (!running) {
            dropped.incrementAndGet();
            return false;
        }
        if (queue.offer(reading)) {
            accepted.incrementAndGet();
            return true;
        }
        dropped.incrementAndGet();
        return false;
    }

    /** @return a snapshot of the counters. */
    public Stats stats() {
        long[] failures = new long[sinks.size()];
        for (int i = 0; i < failures.length; i++) {
            failures[i] = failureCounts.get(i).get();
        }
        return new Stats(accepted.get(), dropped.get(), delivered.get(), queue.size(), failures);
    }

    /** @return the names of the sinks, in invocation order. */
    public List<String> sinkNames() {
        return sinks.stream().map(Sink::name).toList();
    }

    /**
     * Stops accepting readings, lets the workers finish what is already queued, and joins
     * them.
     *
     * <p>Draining rather than discarding matters at shutdown: the readings already in the
     * queue have been acknowledged to the meters that sent them, and dropping them at the
     * last moment would lose data the system said it had.</p>
     */
    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        for (Thread worker : workers) {
            try {
                worker.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** The worker loop: take a reading, give it to every sink, repeat until stopped and empty. */
    private void drain() {
        while (true) {
            Reading reading;
            try {
                reading = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (reading == null) {
                // Nothing queued. If we have been told to stop, the queue is now drained.
                if (!running) {
                    return;
                }
                continue;
            }
            deliver(reading);
        }
    }

    /** Invokes every sink, isolating each from the others' failures. */
    private void deliver(Reading reading) {
        for (int i = 0; i < sinks.size(); i++) {
            Sink sink = sinks.get(i);
            try {
                sink.accept(reading);
            } catch (RuntimeException e) {
                recordFailure(i, sink, e);
            }
        }
        delivered.incrementAndGet();
    }

    /** Counts a sink failure and logs it at most once every {@link #FAILURE_LOG_INTERVAL_MS}. */
    private void recordFailure(int index, Sink sink, RuntimeException failure) {
        long count = failureCounts.get(index).incrementAndGet();
        AtomicLong lastLogged = lastFailureLogMillis.get(index);
        long now = System.currentTimeMillis();
        long previous = lastLogged.get();
        if (now - previous < FAILURE_LOG_INTERVAL_MS || !lastLogged.compareAndSet(previous, now)) {
            return;
        }
        System.err.println("[dispatcher] sink '" + sink.name() + "' failed (" + count
                + " failure(s) so far): " + failure);
    }

    /**
     * An immutable snapshot of the dispatcher's counters, for the server's status line and
     * for the Phase 4 ingest benchmark.
     */
    public static final class Stats {

        private final long accepted;
        private final long dropped;
        private final long delivered;
        private final int queueDepth;
        private final long[] sinkFailures;

        private Stats(long accepted, long dropped, long delivered, int queueDepth, long[] sinkFailures) {
            this.accepted = accepted;
            this.dropped = dropped;
            this.delivered = delivered;
            this.queueDepth = queueDepth;
            this.sinkFailures = sinkFailures;
        }

        /** @return readings queued for delivery since start-up. */
        public long getAccepted() {
            return accepted;
        }

        /** @return readings discarded because the queue was full or the dispatcher had stopped. */
        public long getDropped() {
            return dropped;
        }

        /** @return readings that have been through every sink. */
        public long getDelivered() {
            return delivered;
        }

        /** @return readings currently waiting for a worker. */
        public int getQueueDepth() {
            return queueDepth;
        }

        /**
         * @param sinkIndex position of the sink in the dispatcher's invocation order
         * @return how many times that sink has thrown
         * @throws ArrayIndexOutOfBoundsException if there is no sink at that position
         */
        public long getSinkFailures(int sinkIndex) {
            return sinkFailures[sinkIndex];
        }

        /** @return the total number of sink failures across every sink. */
        public long getTotalSinkFailures() {
            long total = 0;
            for (long failures : sinkFailures) {
                total += failures;
            }
            return total;
        }

        @Override
        public String toString() {
            return "accepted=" + accepted + " delivered=" + delivered + " dropped=" + dropped
                    + " queued=" + queueDepth + " sinkFailures=" + getTotalSinkFailures();
        }
    }
}
