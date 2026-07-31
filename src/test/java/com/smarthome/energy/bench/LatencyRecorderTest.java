package com.smarthome.energy.bench;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the percentile arithmetic the ingest benchmark's latency column depends on.
 *
 * <p>Worth testing rather than eyeballing, because an off-by-one in a percentile index is
 * invisible in the output — the number still looks like a latency — and the whole point of
 * Evidence 1 is that the numbers can be trusted.
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
class LatencyRecorderTest {

    @Test
    @DisplayName("percentiles over a known distribution are the expected values")
    void percentilesOfAKnownDistribution() {
        LatencyRecorder recorder = new LatencyRecorder(1000);
        // 1..100, so the pth percentile is simply p.
        for (int i = 1; i <= 100; i++) {
            recorder.record(i);
        }

        long[] p = recorder.percentiles(0.50, 0.95, 0.99, 1.00);
        assertEquals(50, p[0]);
        assertEquals(95, p[1]);
        assertEquals(99, p[2]);
        assertEquals(100, p[3]);
        assertEquals(100, recorder.getCount());
        assertEquals(50.5, recorder.mean(), 0.001);
    }

    @Test
    @DisplayName("order of arrival does not change the percentiles")
    void isOrderIndependent() {
        LatencyRecorder ascending = new LatencyRecorder(100);
        LatencyRecorder descending = new LatencyRecorder(100);
        for (int i = 1; i <= 100; i++) {
            ascending.record(i);
            descending.record(101 - i);
        }

        assertEquals(ascending.percentiles(0.99)[0], descending.percentiles(0.99)[0]);
    }

    @Test
    @DisplayName("a single tail sample still shows up at p99")
    void tailIsNotAveragedAway() {
        LatencyRecorder recorder = new LatencyRecorder(1000);
        for (int i = 0; i < 99; i++) {
            recorder.record(1);
        }
        recorder.record(5_000);

        assertEquals(1, recorder.percentiles(0.50)[0], "the bulk is 1 ms");
        assertEquals(5_000, recorder.percentiles(1.0)[0], "the stall is still in there");
        assertTrue(recorder.mean() < 100, "a mean would have hidden it, which is why p99 exists");
    }

    @Test
    @DisplayName("an empty recorder reports zeroes rather than failing")
    void emptyIsSafe() {
        LatencyRecorder recorder = new LatencyRecorder(10);
        assertEquals(0, recorder.getCount());
        assertEquals(0L, recorder.percentiles(0.99)[0]);
        assertEquals(0.0, recorder.mean());
    }

    @Test
    @DisplayName("samples past the capacity are counted, not silently dropped or grown into")
    void overflowIsCounted() {
        LatencyRecorder recorder = new LatencyRecorder(4);
        for (int i = 0; i < 10; i++) {
            recorder.record(i);
        }

        assertEquals(4, recorder.getCount());
        assertEquals(6, recorder.getOverflowCount());
    }

    @Test
    @DisplayName("a negative latency — a clock that went backwards — is clamped, not recorded")
    void negativeLatenciesAreClamped() {
        LatencyRecorder recorder = new LatencyRecorder(10);
        recorder.record(-5);
        assertEquals(0L, recorder.percentiles(1.0)[0]);
    }

    @Test
    @DisplayName("a percentile outside (0,1] is refused")
    void refusesImpossiblePercentiles() {
        LatencyRecorder recorder = new LatencyRecorder(10);
        recorder.record(1);
        assertThrows(IllegalArgumentException.class, () -> recorder.percentiles(0.0));
        assertThrows(IllegalArgumentException.class, () -> recorder.percentiles(1.5));
    }

    @Test
    @DisplayName("concurrent recording loses no samples")
    void recordsFromManyThreadsWithoutLoss() throws Exception {
        int threads = 8;
        int perThread = 10_000;
        LatencyRecorder recorder = new LatencyRecorder(threads * perThread);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    recorder.record(1);
                }
                done.countDown();
            });
            workers.add(worker);
            worker.start();
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "the recording threads should have finished");

        // The sink is called from every dispatcher worker at once, so a recorder that lost
        // samples under contention would quietly understate the sample count behind every
        // percentile in the results table.
        assertEquals(threads * perThread, recorder.getCount());
        assertEquals(0, recorder.getOverflowCount());
    }
}
