package com.smarthome.energy.server;

import com.smarthome.energy.model.Reading;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the fan-out seam: that every sink sees every reading, that a slow consumer costs
 * readings rather than blocking the ingest path, that one broken sink does not take the
 * others down with it, and that shutdown drains rather than discards.
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
class ReadingDispatcherTest {

    private static Reading reading(int deviceId) {
        return new Reading(deviceId, Instant.ofEpochMilli(1_721_817_600_000L), 230.0, 1.0, 230.0);
    }

    @Test
    @DisplayName("every sink receives every reading, in registration order")
    void fansOutToEverySink() throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(6);
        List<String> order = new CopyOnWriteArrayList<>();

        try (ReadingDispatcher dispatcher = new ReadingDispatcher(List.of(
                ReadingDispatcher.Sink.of("first", r -> {
                    order.add("first:" + r.getDeviceId());
                    delivered.countDown();
                }),
                ReadingDispatcher.Sink.of("second", r -> {
                    order.add("second:" + r.getDeviceId());
                    delivered.countDown();
                })), 100, 1)) {

            for (int deviceId = 1; deviceId <= 3; deviceId++) {
                assertTrue(dispatcher.dispatch(reading(deviceId)));
            }
            assertTrue(delivered.await(5, TimeUnit.SECONDS), "sinks were not all called");
        }

        assertEquals(6, order.size());
        // One worker, so the readings stay in order and each is fanned out before the next.
        assertEquals(List.of("first:1", "second:1", "first:2", "second:2", "first:3", "second:3"),
                order);
    }

    @Test
    @DisplayName("a full queue drops the arrival instead of blocking the caller")
    void dropsWhenTheQueueIsFull() throws InterruptedException {
        CountDownLatch blockTheWorker = new CountDownLatch(1);
        CountDownLatch workerStarted = new CountDownLatch(1);

        try (ReadingDispatcher dispatcher = new ReadingDispatcher(List.of(
                ReadingDispatcher.Sink.of("slow", r -> {
                    workerStarted.countDown();
                    try {
                        blockTheWorker.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })), 2, 1)) {

            assertTrue(dispatcher.dispatch(reading(1)));
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));

            // The worker is parked inside the sink, so the queue now fills and then refuses.
            assertTrue(dispatcher.dispatch(reading(2)));
            assertTrue(dispatcher.dispatch(reading(3)));
            assertFalse(dispatcher.dispatch(reading(4)), "a full queue must drop, not block");

            assertEquals(1, dispatcher.stats().getDropped());
            blockTheWorker.countDown();
        }
    }

    @Test
    @DisplayName("a sink that throws is counted and isolated from the others")
    void isolatesAFailingSink() throws InterruptedException {
        CountDownLatch healthySawBoth = new CountDownLatch(2);
        AtomicInteger healthyCalls = new AtomicInteger();

        try (ReadingDispatcher dispatcher = new ReadingDispatcher(List.of(
                ReadingDispatcher.Sink.of("broken", r -> {
                    throw new IllegalStateException("the database has gone away");
                }),
                ReadingDispatcher.Sink.of("healthy", r -> {
                    healthyCalls.incrementAndGet();
                    healthySawBoth.countDown();
                })), 100, 1)) {

            dispatcher.dispatch(reading(1));
            dispatcher.dispatch(reading(2));
            assertTrue(healthySawBoth.await(5, TimeUnit.SECONDS),
                    "a failing sink must not stop the ones after it");

            ReadingDispatcher.Stats stats = dispatcher.stats();
            assertEquals(2, healthyCalls.get());
            assertEquals(2, stats.getSinkFailures(0));
            assertEquals(0, stats.getSinkFailures(1));
            assertEquals(2, stats.getTotalSinkFailures());
        }
    }

    @Test
    @DisplayName("close drains what is already queued instead of discarding it")
    void drainsOnClose() {
        AtomicInteger seen = new AtomicInteger();
        ReadingDispatcher dispatcher = new ReadingDispatcher(List.of(
                ReadingDispatcher.Sink.of("counter", r -> seen.incrementAndGet())), 1_000, 1);

        for (int i = 0; i < 200; i++) {
            dispatcher.dispatch(reading(1));
        }
        dispatcher.close();

        assertEquals(200, seen.get());
        assertEquals(200, dispatcher.stats().getDelivered());
        assertEquals(0, dispatcher.stats().getDropped());
    }

    @Test
    @DisplayName("readings dispatched after close are refused rather than silently lost")
    void refusesAfterClose() {
        ReadingDispatcher dispatcher = new ReadingDispatcher(List.of(
                ReadingDispatcher.Sink.of("noop", r -> { })), 10, 1);
        dispatcher.close();

        assertFalse(dispatcher.dispatch(reading(1)));
        assertEquals(1, dispatcher.stats().getDropped());
    }

    @Test
    @DisplayName("a dispatcher with no sinks is a configuration mistake, not a valid state")
    void rejectsAnEmptySinkList() {
        assertThrows(IllegalArgumentException.class, () -> new ReadingDispatcher(List.of()));
    }
}
