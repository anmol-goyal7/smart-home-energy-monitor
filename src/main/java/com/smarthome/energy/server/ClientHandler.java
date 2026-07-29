package com.smarthome.energy.server;

import com.smarthome.energy.model.Reading;
import com.smarthome.energy.protocol.MessageParser;
import com.smarthome.energy.protocol.MeterMessage;
import com.smarthome.energy.protocol.ProtocolException;
import com.smarthome.energy.protocol.WireFormatValidator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * The unit of concurrency: one {@code Runnable} bound to one connected meter socket.
 *
 * <p>{@link EnergyMonitorServer} creates exactly one {@code ClientHandler} per accepted
 * connection and runs it on its own thread. The handler owns the socket's input stream
 * for the life of the connection and loops: read a line, validate it with the DFA
 * ({@link WireFormatValidator}), parse it into a {@link Reading} ({@link MessageParser}),
 * then pass it to the {@link ReadingDispatcher}. Malformed lines are logged and skipped
 * so one bad frame never drops an otherwise healthy connection.</p>
 *
 * <p>Because each connection has its own handler and thread, a slow or stalled meter
 * blocks only its own thread and cannot stall ingest for the others.</p>
 *
 * <h2>Why the line reader is hand-rolled</h2>
 *
 * <p>{@code BufferedReader.readLine()} is the obvious way to read a line-delimited protocol
 * and it has one property that does not suit a server: it grows its buffer until it finds a
 * terminator. A meter that opens a connection and then streams digits without ever sending
 * {@code '\n'} — broken firmware, or a port scanner — would make the server allocate until
 * the heap is gone, and it would take the other five meters down with it. Reading through a
 * cap of {@link #MAX_LINE_LENGTH} characters instead means the worst such a client achieves
 * is its own disconnection. The cap is roughly twenty times the longest legal frame.</p>
 *
 * <p>The reader treats only {@code '\n'} as a terminator, so a meter sending CRLF leaves a
 * trailing {@code '\r'} inside the line and the DFA rejects it, naming the column. That is
 * the intended behaviour: the grammar in {@code MeterMessage} is the contract, and quietly
 * accepting a second line ending would mean the format has two definitions.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals, threading, socket I/O.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class ClientHandler implements Runnable {

    /** Longest line the handler will assemble before giving up on the frame. */
    public static final int MAX_LINE_LENGTH = 1_024;

    /** Minimum gap between two logged rejections on one connection. */
    private static final long REJECT_LOG_INTERVAL_MS = 1_000L;

    private final Socket socket;
    private final String connectionName;
    private final ReadingDispatcher dispatcher;
    private final IntPredicate knownDevice;
    private final Consumer<ClientHandler> onFinished;

    // Stateless collaborators. One instance per connection costs nothing and keeps the
    // constructor short; a single shared instance of each would work just as well.
    private final WireFormatValidator validator = new WireFormatValidator();
    private final MessageParser parser = new MessageParser();

    private final StringBuilder lineBuffer = new StringBuilder(MAX_LINE_LENGTH);
    private final Set<Integer> reportedUnknownDevices = new HashSet<>();
    private int reportedDeviceId;
    private boolean lineOverflowed;
    private long lastRejectLogMillis;
    private long suppressedRejectLogs;

    private long acceptedCount;
    private long rejectedCount;
    private long unparseableCount;
    private long unknownDeviceCount;
    private long droppedCount;

    /**
     * @param socket      the accepted meter connection; must not be null
     * @param id          sequence number used to name the connection in logs
     * @param dispatcher  where validated readings go; must not be null
     * @param knownDevice tests whether a device id exists in the catalogue — readings for
     *                    anything else are refused before they can violate the foreign key;
     *                    must not be null
     * @param onFinished  called once when the connection ends, so the server can forget this
     *                    handler; must not be null
     * @throws NullPointerException if any argument except {@code id} is null
     */
    public ClientHandler(Socket socket, int id, ReadingDispatcher dispatcher,
                         IntPredicate knownDevice, Consumer<ClientHandler> onFinished) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.knownDevice = Objects.requireNonNull(knownDevice, "knownDevice");
        this.onFinished = Objects.requireNonNull(onFinished, "onFinished");
        this.connectionName = "conn-" + id + " (" + socket.getRemoteSocketAddress() + ")";
    }

    /**
     * The label this connection is logged under.
     *
     * <p>Connections are numbered in accept order, which has nothing to do with device ids —
     * the fourth meter to connect may well be device 2. Naming them {@code conn-4} rather
     * than {@code meter-4} keeps the server's log from appearing to contradict the
     * simulator's, and the device id is folded in as soon as the first reading identifies
     * it.</p>
     *
     * @return e.g. {@code conn-4 (/127.0.0.1:46920) device=2}
     */
    public String getName() {
        return reportedDeviceId == 0 ? connectionName : connectionName + " device=" + reportedDeviceId;
    }

    /** @return readings validated, parsed, and handed to the dispatcher. */
    public long getAcceptedCount() {
        return acceptedCount;
    }

    /** @return lines the DFA refused. */
    public long getRejectedCount() {
        return rejectedCount;
    }

    /** @return lines that were well-formed but carried an unusable value. */
    public long getUnparseableCount() {
        return unparseableCount;
    }

    /** @return readings refused because their device id is not in the catalogue. */
    public long getUnknownDeviceCount() {
        return unknownDeviceCount;
    }

    /** @return readings the dispatcher had no room for. */
    public long getDroppedCount() {
        return droppedCount;
    }

    /**
     * Closes the connection, which unblocks the read this handler is parked in.
     *
     * <p>Interrupting the thread would not do it — a thread blocked in a socket read does not
     * observe its interrupt flag — so shutdown works by closing the socket underneath it and
     * letting the resulting exception end the loop.</p>
     */
    public void shutdown() {
        try {
            socket.close();
        } catch (IOException e) {
            // Already going away.
        }
    }

    @Override
    public void run() {
        System.out.println("[server] " + getName() + " connected");
        try (Socket managed = socket;
             Reader in = new BufferedReader(new InputStreamReader(
                     managed.getInputStream(), StandardCharsets.US_ASCII))) {

            readLoop(in);

        } catch (IOException e) {
            if (!socket.isClosed()) {
                System.err.println("[server] " + getName() + " read failed: " + e.getMessage());
            }
        } finally {
            System.out.println("[server] " + getName() + " disconnected — " + summary());
            onFinished.accept(this);
        }
    }

    /** Reads, validates, parses, and dispatches until the meter goes away. */
    private void readLoop(Reader in) throws IOException {
        String line;
        while ((line = readLine(in)) != null) {
            if (lineOverflowed) {
                rejectedCount++;
                logRejection("frame exceeded " + MAX_LINE_LENGTH
                        + " characters without a terminator; discarded");
                continue;
            }
            if (line.isEmpty()) {
                // A bare terminator. Not legal, but not worth a diagnostic either.
                rejectedCount++;
                continue;
            }

            WireFormatValidator.ValidationResult verdict = validator.validateLine(line);
            if (!verdict.isAccepted()) {
                rejectedCount++;
                logRejection(System.lineSeparator() + verdict.describe(line));
                continue;
            }

            Reading reading;
            try {
                reading = parser.parse(line);
            } catch (ProtocolException e) {
                unparseableCount++;
                logRejection(e.getMessage());
                continue;
            }

            if (!knownDevice.test(reading.getDeviceId())) {
                unknownDeviceCount++;
                if (reportedUnknownDevices.add(reading.getDeviceId())) {
                    System.err.println("[server] " + getName() + " sent readings for device "
                            + reading.getDeviceId() + ", which is not in the catalogue; ignoring them");
                }
                continue;
            }

            if (reportedDeviceId == 0) {
                reportedDeviceId = reading.getDeviceId();
            }

            if (dispatcher.dispatch(reading)) {
                acceptedCount++;
            } else {
                droppedCount++;
            }
        }
    }

    /**
     * Reads one line, up to {@link #MAX_LINE_LENGTH} characters.
     *
     * <p>Sets {@link #lineOverflowed} when the cap was hit; in that case the rest of the
     * frame is consumed and discarded so the next line starts on a frame boundary rather
     * than in the middle of the one that was too long.</p>
     *
     * @return the line without its terminator, or null at end of stream
     */
    private String readLine(Reader in) throws IOException {
        lineBuffer.setLength(0);
        lineOverflowed = false;

        int c;
        while ((c = in.read()) != -1) {
            if (c == MeterMessage.TERMINATOR) {
                return lineBuffer.toString();
            }
            if (lineBuffer.length() < MAX_LINE_LENGTH) {
                lineBuffer.append((char) c);
            } else {
                lineOverflowed = true;
            }
        }
        // End of stream. A non-empty buffer is a frame the meter died in the middle of; hand
        // it back so it is reported as truncated rather than silently forgotten.
        return lineBuffer.length() == 0 && !lineOverflowed ? null : lineBuffer.toString();
    }

    /**
     * Logs a rejection, at most one per {@link #REJECT_LOG_INTERVAL_MS}.
     *
     * <p>The diagnostic is the point of the DFA, so it is printed in full — but a meter
     * emitting nothing but garbage would otherwise drown every other message in the console,
     * so the suppressed ones are counted and reported with the next line that gets through.</p>
     */
    private void logRejection(String detail) {
        long now = System.currentTimeMillis();
        if (now - lastRejectLogMillis < REJECT_LOG_INTERVAL_MS) {
            suppressedRejectLogs++;
            return;
        }
        lastRejectLogMillis = now;
        String suffix = suppressedRejectLogs == 0 ? ""
                : " (" + suppressedRejectLogs + " similar rejection(s) not shown)";
        suppressedRejectLogs = 0;
        System.err.println("[server] " + getName() + " rejected a frame" + suffix + ": " + detail);
    }

    /** @return the per-connection counters, as printed when the connection ends. */
    public String summary() {
        return "accepted=" + acceptedCount
                + " rejected=" + rejectedCount
                + " unparseable=" + unparseableCount
                + " unknownDevice=" + unknownDeviceCount
                + " dropped=" + droppedCount;
    }

    @Override
    public String toString() {
        return "ClientHandler[" + getName() + ", " + summary() + "]";
    }
}
