package com.smarthome.energy.client;

import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.Reading;
import com.smarthome.energy.protocol.MessageParser;
import com.smarthome.energy.protocol.MeterMessage;
import com.smarthome.energy.protocol.ProtocolException;
import com.smarthome.energy.protocol.WireFormatValidator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * TCP client that subscribes to the server's live reading feed.
 *
 * <p>Connects to the server's dashboard live-feed port, sends the subscribe handshake, and
 * then reads streamed readings on a background thread. Each reading is handed to the
 * controller, which updates the model; the view repaints on the Swing event dispatch
 * thread. Historical data is fetched separately by {@link HistoryQueryService}; this class
 * carries only the real-time stream.</p>
 *
 * <p>The feed carries the same frames the meters send, so this decodes them with the same
 * {@link WireFormatValidator} and {@link MessageParser} the server uses. The dashboard
 * therefore has no protocol code of its own to keep in step — a change to the wire format is
 * a change to one grammar and one parser.</p>
 *
 * <p>It also carries the rule engine's alerts, as {@code ALT} frames on the same connection.
 * That is what keeps an alert behind the reading that raised it: two channels would let the
 * alert about a 264 V reading arrive before the 264 V itself, and a tile would go red for a
 * value it was not showing.</p>
 *
 * <h2>Reconnection</h2>
 *
 * <p>The client retries indefinitely with a short backoff rather than giving up on the first
 * failure, and reports each transition through {@link Listener#connectionStateChanged}. A
 * dashboard is normally opened before or alongside the server and left running across
 * restarts of it; a UI that has to be relaunched every time the server bounces is a UI nobody
 * keeps open, and an operator who cannot tell "no alerts" from "no connection" is worse off
 * than one with no dashboard at all.</p>
 *
 * <p>Every callback arrives on this client's own thread, never the EDT. Marshalling is the
 * controller's job, and it is the only place it happens.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming (background networking off the EDT);
 * Unit I — TCP client sockets.</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
public final class LiveFeedClient {

    /** How long to wait for the server to accept the connection. */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /** Delay before the first reconnection attempt. */
    private static final long RETRY_BASE_MS = 1_000L;

    /** Longest the backoff is allowed to grow to. */
    private static final long RETRY_MAX_MS = 10_000L;

    /** What the feed reports to whoever is driving it. */
    public interface Listener {

        /**
         * @param reading a reading that just arrived; called on the feed's own thread
         */
        void readingReceived(Reading reading);

        /**
         * @param alert an alert the rule engine raised, delivered on the same connection and
         *              in order behind the reading that caused it; called on the feed's own
         *              thread. The event carries no {@code triggeringReadingId} — the frame
         *              does not carry the database key
         */
        void alertReceived(Event alert);

        /**
         * @param connected whether the feed is now up
         * @param detail    a short human-readable explanation
         */
        void connectionStateChanged(boolean connected, String detail);
    }

    private final String host;
    private final int port;
    private final Listener listener;
    private final WireFormatValidator validator = new WireFormatValidator();
    private final MessageParser parser = new MessageParser();

    private volatile boolean running;
    private volatile Socket socket;
    private Thread thread;
    private long receivedCount;
    private long alertCount;
    private long rejectedCount;

    /**
     * @param host     the server's host; must not be null
     * @param port     the server's live-feed port
     * @param listener where readings and connection changes are reported; must not be null
     * @throws NullPointerException if a reference argument is null
     */
    public LiveFeedClient(String host, int port, Listener listener) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Starts the background thread that keeps the subscription up.
     *
     * @throws IllegalStateException if already started
     */
    public void start() {
        if (running) {
            throw new IllegalStateException("live feed already started");
        }
        running = true;
        thread = new Thread(this::connectLoop, "live-feed");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops the feed and closes the connection. */
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
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** @return readings decoded from the feed since start-up. */
    public long getReceivedCount() {
        return receivedCount;
    }

    /** @return alerts decoded from the feed since start-up. */
    public long getAlertCount() {
        return alertCount;
    }

    /** @return frames from the feed that failed validation or parsing. */
    public long getRejectedCount() {
        return rejectedCount;
    }

    /** @return the address this feed subscribes to, for the status bar. */
    public String getEndpoint() {
        return host + ":" + port;
    }

    /** Connects, streams, and reconnects with backoff until stopped. */
    private void connectLoop() {
        long backoff = RETRY_BASE_MS;
        while (running) {
            try (Socket connection = new Socket()) {
                socket = connection;
                connection.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                connection.setTcpNoDelay(true);

                try (BufferedReader in = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.US_ASCII));
                     Writer out = new BufferedWriter(new OutputStreamWriter(
                             connection.getOutputStream(), StandardCharsets.US_ASCII))) {

                    // Deliberately not `if (!subscribe) continue;` — that would skip the
                    // backoff below and spin on a server that keeps answering wrongly.
                    if (subscribe(in, out)) {
                        backoff = RETRY_BASE_MS;
                        listener.connectionStateChanged(true, "subscribed to " + getEndpoint());
                        readFrames(in);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    listener.connectionStateChanged(false, "live feed unavailable at " + getEndpoint()
                            + " (" + e.getMessage() + ")");
                }
            } finally {
                socket = null;
            }

            if (!running) {
                return;
            }
            listener.connectionStateChanged(false, "reconnecting to " + getEndpoint() + " in "
                    + (backoff / 1000) + "s");
            if (!sleepQuietly(backoff)) {
                return;
            }
            backoff = Math.min(backoff * 2, RETRY_MAX_MS);
        }
    }

    /** Sends the subscribe line and checks the acknowledgement. */
    private boolean subscribe(BufferedReader in, Writer out) throws IOException {
        out.write(MeterMessage.SUBSCRIBE_COMMAND);
        out.write(MeterMessage.TERMINATOR);
        out.flush();

        String ack = in.readLine();
        if (!MeterMessage.SUBSCRIBE_ACK.equals(ack)) {
            listener.connectionStateChanged(false, "server answered '" + ack + "' instead of '"
                    + MeterMessage.SUBSCRIBE_ACK + "'");
            return false;
        }
        return true;
    }

    /** Reads frames until the feed closes, decoding each with the shared protocol classes. */
    private void readFrames(BufferedReader in) throws IOException {
        String line;
        while (running && (line = in.readLine()) != null) {
            if (MeterMessage.isAlertFrame(line)) {
                readAlert(line);
                continue;
            }
            if (!validator.validateLine(line).isAccepted()) {
                rejectedCount++;
                continue;
            }
            try {
                Reading reading = parser.parse(line);
                receivedCount++;
                listener.readingReceived(reading);
            } catch (ProtocolException e) {
                rejectedCount++;
            }
        }
        if (running) {
            listener.connectionStateChanged(false, "server closed the live feed");
        }
    }

    /**
     * Decodes one alert frame.
     *
     * <p>The automaton is not consulted here: it recognises the meter grammar, which is a
     * different language from the alert frame's. {@code parseAlert} does the whole check
     * instead, and a frame it rejects is counted with the malformed readings — the dashboard
     * treats "the server said something I do not understand" the same way whatever the
     * something was.</p>
     */
    private void readAlert(String line) {
        try {
            Event alert = parser.parseAlert(line);
            alertCount++;
            listener.alertReceived(alert);
        } catch (ProtocolException e) {
            rejectedCount++;
        }
    }

    private boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
