package com.smarthome.energy.bench;

import com.smarthome.energy.model.Reading;
import com.smarthome.energy.protocol.MeterMessage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A load-generating meter for {@link IngestBenchmark}: one TCP client streaming well-formed
 * frames at a fixed rate.
 *
 * <p>This is deliberately not a {@code MeterSimulator}. The simulator's job is to produce
 * <em>plausible</em> readings — duty cycles, Gaussian jitter, injected anomalies — and every
 * one of those is a call to {@code Random} on the client side of a measurement whose subject
 * is the server. Here the values are fixed and the only thing that varies per frame is the
 * timestamp, so the client costs the same on every tick and the difference between two runs
 * is the server's, not the generator's.</p>
 *
 * <p>The frame is still built by {@link MeterMessage#format(Reading)}, so what goes on the
 * wire is exactly what a real meter sends and the server's DFA does the same work it always
 * does.</p>
 *
 * <h2>Blocking on write is a result, not a bug</h2>
 *
 * <p>When a connection is accepted but never read from — which is what a fixed pool does to
 * every meter past its worker count — the kernel's socket buffer fills and this meter's
 * {@code write} blocks. It is left to block on purpose. Dropping the frame instead would make
 * the meter's offered rate look identical under both strategies and hide the effect the
 * benchmark exists to measure; blocking makes it show up as the gap between
 * {@link #getSentCount()} and the rate that was asked for.</p>
 *
 * <p>Syllabus mapping: Unit I — TCP client sockets, threading.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
final class SyntheticMeter implements Runnable {

    /** How long to wait for the server to accept the connection. */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /** Fixed values every frame carries; nominal and well inside every threshold. */
    private static final double VOLTAGE = 230.00;
    private static final double CURRENT = 4.35;
    private static final double POWER = 1000.50;

    private final int deviceId;
    private final String host;
    private final int port;
    private final long intervalMillis;
    private final AtomicLong sent = new AtomicLong();

    private volatile boolean running = true;
    private volatile Socket socket;
    private volatile boolean connected;

    /**
     * @param deviceId       the device id this meter reports as; must be positive
     * @param host           server host; must not be null
     * @param port           the ingest port
     * @param intervalMillis milliseconds between frames; must be positive
     * @throws IllegalArgumentException if the id or the interval is not positive
     */
    SyntheticMeter(int deviceId, String host, int port, long intervalMillis) {
        if (deviceId <= 0) {
            throw new IllegalArgumentException("deviceId must be positive, was " + deviceId);
        }
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis must be positive, was " + intervalMillis);
        }
        this.deviceId = deviceId;
        this.host = host;
        this.port = port;
        this.intervalMillis = intervalMillis;
    }

    /** @return frames handed to the socket since the meter started. */
    long getSentCount() {
        return sent.get();
    }

    /** @return true once the TCP connection has been established. */
    boolean isConnected() {
        return connected;
    }

    /** Stops the stream and closes the socket, unblocking a write that is parked in the kernel. */
    void stop() {
        running = false;
        Socket open = socket;
        if (open != null) {
            try {
                open.close();
            } catch (IOException e) {
                // Shutting down.
            }
        }
    }

    @Override
    public void run() {
        try (Socket connection = new Socket()) {
            socket = connection;
            connection.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            connection.setTcpNoDelay(true);
            connected = true;

            try (Writer out = new BufferedWriter(new OutputStreamWriter(
                    connection.getOutputStream(), StandardCharsets.US_ASCII))) {
                stream(out);
            }
        } catch (IOException e) {
            // A close during teardown, or a buffer that never drained. Either way the run is
            // over and the counters already say what this meter managed.
        }
    }

    /** Writes one frame per tick on an absolute schedule until stopped. */
    private void stream(Writer out) throws IOException {
        long nextTickMillis = System.currentTimeMillis();
        while (running) {
            long now = System.currentTimeMillis();
            String frame = MeterMessage.format(
                    Reading.fromEpochMillis(deviceId, now, VOLTAGE, CURRENT, POWER));

            out.write(frame);
            out.flush();
            sent.incrementAndGet();

            nextTickMillis += intervalMillis;
            long pause = nextTickMillis - System.currentTimeMillis();
            if (pause > 0) {
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                // Behind schedule — a blocked write, or simply more meters than cores. Re-base
                // rather than burst, so a recovered meter does not double-count its backlog.
                nextTickMillis = System.currentTimeMillis();
            }
        }
    }

    @Override
    public String toString() {
        return "SyntheticMeter[device=" + deviceId + ", sent=" + sent.get()
                + ", connected=" + connected + "]";
    }
}
