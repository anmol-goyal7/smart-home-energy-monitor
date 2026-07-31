package com.smarthome.energy.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unit I: what happens to a counter that several handler threads increment at once.
 *
 * <p>{@code ReadingDispatcher} keeps three counters — accepted, dropped, delivered — and every
 * one of them is an {@link AtomicLong}. A plain {@code long} would be shorter to write and
 * would look correct in every single-threaded test, which is exactly why this demonstration
 * exists: the server has one handler thread per meter, and they all report to the same
 * counters.</p>
 *
 * <p>{@code count++} is three operations — read, add one, write back — and nothing stops a
 * second thread reading the same value between the first thread's read and its write. Both
 * then write the same result, and one increment is gone. The loss is not an error anyone
 * sees; it is a total that is quietly too small, which for a monitoring system means
 * under-reporting exactly when load is highest.</p>
 *
 * <p>The run below is deterministic in shape though not in magnitude: the count is always
 * short, by an amount that depends on how the scheduler interleaved the threads that
 * afternoon. That variability is itself the point — a bug that loses a different number of
 * updates every run is one no test asserts its way to.</p>
 *
 * <p>Syllabus mapping: Unit I — Java concurrency (data races, atomicity).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
final class LostUpdateDemo {

    /** Threads incrementing the counters, standing in for one handler per connected meter. */
    private static final int THREADS = 8;

    /** Increments each thread performs — one per reading it would have dispatched. */
    private static final int INCREMENTS_PER_THREAD = 200_000;

    private LostUpdateDemo() {
        // Static entry point only.
    }

    /**
     * Runs both counters under the same concurrent load and prints what each ended up with.
     *
     * @return true if the demonstration behaved as expected: the unsynchronised counter lost
     *         at least one update and the atomic one lost none
     */
    static boolean run() {
        long expected = (long) THREADS * INCREMENTS_PER_THREAD;
        System.out.println(THREADS + " threads x " + String.format(Locale.ROOT, "%,d",
                INCREMENTS_PER_THREAD) + " increments each — the correct total is "
                + String.format(Locale.ROOT, "%,d", expected));
        System.out.println();

        UnsafeCounter unsafe = new UnsafeCounter();
        drive(unsafe::increment);
        long unsafeTotal = unsafe.get();

        AtomicLong safe = new AtomicLong();
        drive(safe::incrementAndGet);
        long safeTotal = safe.get();

        long lost = expected - unsafeTotal;
        System.out.printf(Locale.ROOT, "  BROKEN     plain long, count++      %,15d  (%,d lost, %.2f%%)%n",
                unsafeTotal, lost, 100.0 * lost / expected);
        System.out.printf(Locale.ROOT, "  CORRECTED  AtomicLong, incrementAndGet %,13d  (%s)%n",
                safeTotal, safeTotal == expected ? "exact" : "WRONG — expected " + expected);
        System.out.println();

        if (lost > 0) {
            System.out.println("The broken counter is short because count++ is read, add, write —");
            System.out.println("and two threads that read the same value both write the same result.");
        } else {
            System.out.println("The unsynchronised counter happened to come out exact on this run.");
            System.out.println("That is the hazard, not a refutation: the race is still there, and a");
            System.out.println("machine with more cores or a busier scheduler will expose it. Re-run.");
        }
        return lost > 0 && safeTotal == expected;
    }

    /** Runs {@code work} on every thread {@link #INCREMENTS_PER_THREAD} times and joins. */
    private static void drive(Runnable work) {
        List<Thread> threads = new ArrayList<>(THREADS);
        for (int i = 0; i < THREADS; i++) {
            Thread thread = new Thread(() -> {
                for (int n = 0; n < INCREMENTS_PER_THREAD; n++) {
                    work.run();
                }
            }, "lost-update-" + i);
            threads.add(thread);
        }
        threads.forEach(Thread::start);
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * The counter the dispatcher does not use.
     *
     * <p>Not {@code volatile}, and that is deliberate rather than an additional oversight:
     * {@code volatile} would fix the visibility of the field and leave the increment just as
     * broken, which is the more instructive mistake of the two.</p>
     */
    private static final class UnsafeCounter {

        private long count;

        void increment() {
            count++;
        }

        long get() {
            return count;
        }
    }
}
