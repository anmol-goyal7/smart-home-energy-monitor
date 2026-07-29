package com.smarthome.energy.simulator;

import com.smarthome.energy.protocol.MeterMessage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Random;

/**
 * Simulates one appliance's smart meter: a single TCP client that streams readings.
 *
 * <p>Each {@code MeterSimulator} opens its own connection to the server's meter port and,
 * on a fixed interval, generates a reading from its {@link ApplianceProfile} (via
 * {@link WaveformGenerator}), formats it in the wire format defined by
 * {@link MeterMessage}, and writes it to the socket. Running one simulator per
 * appliance is what produces the many concurrent connections the multithreaded server is
 * built to handle.</p>
 *
 * <p>The generator occasionally injects out-of-band values (a spike, a sag, an overload)
 * so the rule engine has anomalies to detect during a demo.</p>
 *
 * <h2>Two details that matter more than they look</h2>
 *
 * <p><strong>The tick schedule is absolute, not relative.</strong> Sleeping for the full
 * interval after each send makes every send late by however long the send took, and the
 * error accumulates — after an hour a "one per second" meter has quietly become one per 1.1
 * seconds, which shows up as a gap in the analytics that nothing in the code explains.
 * Sleeping until the next scheduled instant instead keeps the long-run rate exact.</p>
 *
 * <p><strong>Corruption is a deliberate, off-by-default feature.</strong> With
 * {@code --corrupt p} a proportion of frames have one character overwritten before being
 * written to the socket. It exists to exercise the server's rejection path on demand — the
 * DFA's error-locating diagnostic is a claim about behaviour, and this is how the claim gets
 * demonstrated rather than asserted.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals, TCP client sockets, threading.</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public final class MeterSimulator implements Runnable {

    /** How long to wait for the server to accept the connection. */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /** Attempts made to reach the server before the meter gives up. */
    private static final int CONNECT_ATTEMPTS = 5;

    /** Pause between connection attempts. */
    private static final long CONNECT_RETRY_MS = 2_000L;

    /** Characters a corrupted frame can have a character replaced by. */
    private static final char[] CORRUPTION_ALPHABET = {'x', 'Q', '?', '*', '-', '|', '.', ','};

    private final ApplianceProfile profile;
    private final String host;
    private final int port;
    private final long intervalMillis;
    private final WaveformGenerator generator;
    private final double corruptionProbability;
    private final Random corruption;

    private volatile boolean running = true;
    private volatile Socket socket;

    private long sentCount;
    private long anomalyCount;
    private long corruptedCount;

    /**
     * @param profile               the appliance to model; must not be null
     * @param host                  server host; must not be null
     * @param port                  the server's meter ingest port
     * @param intervalMillis        milliseconds between readings; must be positive
     * @param generator             source of sample values; must not be null
     * @param corruptionProbability probability in {@code [0,1]} that a frame is deliberately
     *                              damaged before being sent
     * @param seed                  seed for the corruption draw, so a run is reproducible
     * @throws NullPointerException     if a reference argument is null
     * @throws IllegalArgumentException if the interval or the probability is out of range
     */
    public MeterSimulator(ApplianceProfile profile, String host, int port, long intervalMillis,
                          WaveformGenerator generator, double corruptionProbability, long seed) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis must be positive, was " + intervalMillis);
        }
        if (corruptionProbability < 0.0 || corruptionProbability > 1.0) {
            throw new IllegalArgumentException("corruptionProbability must be within [0,1], was "
                    + corruptionProbability);
        }
        this.profile = Objects.requireNonNull(profile, "profile");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.intervalMillis = intervalMillis;
        this.generator = Objects.requireNonNull(generator, "generator");
        this.corruptionProbability = corruptionProbability;
        this.corruption = new Random(seed);
    }

    /** @return the appliance this meter reports for. */
    public ApplianceProfile getProfile() {
        return profile;
    }

    /** @return frames written to the socket. */
    public long getSentCount() {
        return sentCount;
    }

    /** @return frames that carried an injected anomaly. */
    public long getAnomalyCount() {
        return anomalyCount;
    }

    /** @return frames deliberately damaged before being sent. */
    public long getCorruptedCount() {
        return corruptedCount;
    }

    /** Stops the stream and closes the connection. */
    public void stop() {
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
        try (Socket connection = connect()) {
            if (connection == null) {
                return;
            }
            socket = connection;
            try (Writer out = new BufferedWriter(new OutputStreamWriter(
                    connection.getOutputStream(), StandardCharsets.US_ASCII))) {
                stream(out);
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("[" + label() + "] connection failed: " + e.getMessage());
            }
        } finally {
            System.out.println("[" + label() + "] stopped after " + sentCount + " reading(s), "
                    + anomalyCount + " anomal(ies), " + corruptedCount + " corrupted frame(s)");
        }
    }

    /** Connects, retrying a few times so a simulator started just before the server still works. */
    private Socket connect() {
        for (int attempt = 1; attempt <= CONNECT_ATTEMPTS && running; attempt++) {
            Socket candidate = new Socket();
            try {
                candidate.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                candidate.setTcpNoDelay(true);
                System.out.println("[" + label() + "] connected to " + host + ":" + port);
                return candidate;
            } catch (IOException e) {
                closeQuietly(candidate);
                System.err.println("[" + label() + "] attempt " + attempt + "/" + CONNECT_ATTEMPTS
                        + " to reach " + host + ":" + port + " failed: " + e.getMessage());
                if (attempt == CONNECT_ATTEMPTS || !sleepQuietly(CONNECT_RETRY_MS)) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Generates and writes one frame per tick until stopped. */
    private void stream(Writer out) throws IOException {
        long nextTickMillis = System.currentTimeMillis();
        while (running) {
            Instant now = Instant.now();
            WaveformGenerator.Sample sample = generator.next(profile, now);
            String frame = MeterMessage.format(sample.reading());

            if (shouldCorrupt()) {
                frame = corrupt(frame);
                corruptedCount++;
            }

            out.write(frame);
            out.flush();
            sentCount++;
            if (sample.isAnomalous()) {
                anomalyCount++;
                System.out.println("[" + label() + "] injected " + sample.anomaly() + ": "
                        + sample.reading());
            }

            nextTickMillis += intervalMillis;
            long pause = nextTickMillis - System.currentTimeMillis();
            if (pause > 0 && !sleepQuietly(pause)) {
                return;
            }
            if (pause <= 0) {
                // Fell behind (a long GC, a stalled write). Re-base rather than burst to catch up.
                nextTickMillis = System.currentTimeMillis();
            }
        }
    }

    private boolean shouldCorrupt() {
        return corruptionProbability > 0.0 && corruption.nextDouble() < corruptionProbability;
    }

    /**
     * Overwrites one character of the frame, leaving the terminator alone so the server still
     * sees a complete line and reports where the damage is instead of waiting for more input.
     */
    private String corrupt(String frame) {
        int body = frame.length() - 1;
        int index = corruption.nextInt(body);
        char replacement = CORRUPTION_ALPHABET[corruption.nextInt(CORRUPTION_ALPHABET.length)];
        if (replacement == frame.charAt(index)) {
            replacement = 'Z';
        }
        return frame.substring(0, index) + replacement + frame.substring(index + 1);
    }

    private boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
            return false;
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Nothing to recover.
        }
    }

    private String label() {
        return "meter-" + profile.getDeviceId() + " " + profile.getName();
    }
}
