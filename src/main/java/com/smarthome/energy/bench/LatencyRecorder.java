package com.smarthome.energy.bench;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects per-reading latencies from the dispatcher's worker threads and reports
 * percentiles over them.
 *
 * <p>Every sample is kept rather than summarised on the fly, because the interesting part of
 * an ingest latency distribution is its tail: a mean hides the case this benchmark exists to
 * find, where most readings arrive promptly and a starved connection's readings arrive
 * seconds late. Keeping the samples costs eight bytes each and lets the percentile be exact
 * instead of estimated.</p>
 *
 * <p>The array is preallocated and the cursor is atomic, so recording is one increment and
 * one store with no allocation and no lock on the measurement path. Samples past the
 * capacity are counted and discarded rather than growing the array mid-measurement — a
 * reallocation inside the timed window would show up in the very numbers being collected.</p>
 *
 * <p>Latencies are whole milliseconds because that is the resolution of the meter-side
 * timestamp the wire format carries; see {@link IngestBenchmark} for what that does and does
 * not let the result say.</p>
 *
 * <p>Syllabus mapping: Unit I — concurrency (lock-free accumulation).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
final class LatencyRecorder {

    private final long[] samples;
    private final AtomicInteger cursor = new AtomicInteger();
    private final AtomicInteger overflow = new AtomicInteger();

    /**
     * @param capacity how many samples to keep; must be positive
     * @throws IllegalArgumentException if {@code capacity} is not positive
     */
    LatencyRecorder(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        this.samples = new long[capacity];
    }

    /**
     * Records one latency.
     *
     * @param millis the observed latency in milliseconds; negative values (a clock that went
     *               backwards) are clamped to zero
     */
    void record(long millis) {
        int index = cursor.getAndIncrement();
        if (index >= samples.length) {
            overflow.incrementAndGet();
            return;
        }
        samples[index] = Math.max(0L, millis);
    }

    /** @return how many samples were recorded, ignoring any that overflowed the capacity. */
    int getCount() {
        return Math.min(cursor.get(), samples.length);
    }

    /** @return how many samples arrived after the capacity was full and were discarded. */
    int getOverflowCount() {
        return overflow.get();
    }

    /**
     * Sorts a snapshot of the samples and reads the requested percentiles off it.
     *
     * @param percentiles the fractions wanted, each within {@code (0,1]}, e.g. {@code 0.99}
     * @return one latency in milliseconds per requested percentile; all zero when nothing was
     *         recorded
     * @throws IllegalArgumentException if a percentile is outside {@code (0,1]}
     */
    long[] percentiles(double... percentiles) {
        long[] result = new long[percentiles.length];
        int count = getCount();
        if (count == 0) {
            return result;
        }
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        for (int i = 0; i < percentiles.length; i++) {
            double p = percentiles[i];
            if (p <= 0.0 || p > 1.0) {
                throw new IllegalArgumentException("percentile must be within (0,1], was " + p);
            }
            int index = (int) Math.ceil(p * count) - 1;
            result[i] = sorted[Math.max(0, Math.min(count - 1, index))];
        }
        return result;
    }

    /** @return the arithmetic mean latency in milliseconds, or zero when nothing was recorded. */
    double mean() {
        int count = getCount();
        if (count == 0) {
            return 0.0;
        }
        long total = 0L;
        for (int i = 0; i < count; i++) {
            total += samples[i];
        }
        return total / (double) count;
    }

    @Override
    public String toString() {
        return "LatencyRecorder[n=" + getCount() + ", overflow=" + getOverflowCount() + "]";
    }
}
