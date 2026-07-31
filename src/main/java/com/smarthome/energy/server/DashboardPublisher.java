package com.smarthome.energy.server;

import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.Reading;
import com.smarthome.energy.protocol.MeterMessage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Broadcasts live readings to connected dashboard subscribers.
 *
 * <p>The Swing dashboard connects to the server on the live-feed port and identifies
 * itself with a subscribe handshake ({@link MeterMessage#SUBSCRIBE_COMMAND}, answered with
 * {@link MeterMessage#SUBSCRIBE_ACK}). This publisher keeps the set of subscriber sockets
 * and, each time the {@link ReadingDispatcher} hands it a reading, writes that reading to
 * every subscriber. Historical data is served separately by the dashboard's own JDBC
 * queries; this class carries only the real-time stream.</p>
 *
 * <p>The feed reuses the meter wire format rather than inventing a second encoding, so the
 * dashboard decodes it with the same {@code WireFormatValidator} and {@code MessageParser}
 * the server uses on the way in — one grammar, one parser, tested once.</p>
 *
 * <h2>Why each subscriber gets a queue and a thread</h2>
 *
 * <p>The obvious implementation writes to every subscriber socket directly from
 * {@link #publish(Reading)}. That call runs on a dispatcher worker, and a socket write
 * blocks once the receiver stops draining its end — so a dashboard that has been paused in a
 * debugger, or is on a laptop that just went to sleep, would stall the dispatcher worker,
 * back up the dispatcher's queue, and eventually stop the database writes too. A monitoring
 * system whose recorder can be halted by one bored viewer is not a monitoring system.</p>
 *
 * <p>So {@link #publish(Reading)} only offers the frame to a bounded per-subscriber queue and
 * returns; each subscriber's own thread does the blocking write. A stalled subscriber fills
 * its queue, its frames are dropped and counted, and nothing else in the server notices.
 * Dead subscribers are pruned when their write fails.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals, threading, socket I/O.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class DashboardPublisher implements AutoCloseable {

    /**
     * Handles a command a subscribed dashboard sent back up the feed.
     *
     * <p>The feed is a broadcast channel in all but one respect: the threshold editor needs to
     * tell the server that the {@code thresholds} table has changed. Rather than open a second
     * port for one word, the connection the dashboard already has becomes bidirectional after
     * the handshake, and this is what the server plugs into it.</p>
     */
    @FunctionalInterface
    public interface CommandHandler {

        /**
         * Carries out one command.
         *
         * @param command the line the dashboard sent, trimmed; never null
         * @return the line to answer with — {@link MeterMessage#RELOAD_ACK}, or something
         *         beginning {@link MeterMessage#ERROR_PREFIX} if the command failed
         */
        String handle(String command);
    }

    /** Frames buffered for one subscriber before its slowness starts costing it readings. */
    public static final int DEFAULT_SUBSCRIBER_QUEUE = 500;

    /** How long a subscriber has to send its handshake before the connection is dropped. */
    private static final int HANDSHAKE_TIMEOUT_MS = 5_000;

    /** How long a subscriber's writer waits on an empty outbox before re-checking for shutdown. */
    private static final long POLL_TIMEOUT_MS = 200L;

    private final int port;
    private final int subscriberQueueCapacity;
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicInteger connectionCounter = new AtomicInteger();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong publishedAlerts = new AtomicLong();
    private final AtomicLong droppedFrames = new AtomicLong();

    private volatile boolean running;
    private volatile CommandHandler commandHandler;
    private ServerSocket listener;
    private Thread acceptThread;

    /**
     * @param port TCP port to listen for dashboards on
     */
    public DashboardPublisher(int port) {
        this(port, DEFAULT_SUBSCRIBER_QUEUE);
    }

    /**
     * @param port                    TCP port to listen for dashboards on
     * @param subscriberQueueCapacity frames buffered per subscriber; must be positive
     * @throws IllegalArgumentException if the capacity is not positive
     */
    public DashboardPublisher(int port, int subscriberQueueCapacity) {
        if (subscriberQueueCapacity <= 0) {
            throw new IllegalArgumentException("subscriberQueueCapacity must be positive, was "
                    + subscriberQueueCapacity);
        }
        this.port = port;
        this.subscriberQueueCapacity = subscriberQueueCapacity;
    }

    /**
     * Opens the live-feed port and begins accepting dashboards.
     *
     * @throws IOException           if the port cannot be bound
     * @throws IllegalStateException if already started
     */
    public void start() throws IOException {
        if (running) {
            throw new IllegalStateException("publisher already started on port " + port);
        }
        listener = new ServerSocket(port);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "dashboard-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        System.out.println("[publisher] live feed listening on port " + port);
    }

    /**
     * Offers a reading to every subscriber. Never blocks and never throws: a subscriber that
     * cannot keep up loses frames, and one that has gone away is pruned.
     *
     * @param reading the reading to broadcast; must not be null
     * @throws NullPointerException if {@code reading} is null
     */
    public void publish(Reading reading) {
        Objects.requireNonNull(reading, "reading");
        if (subscribers.isEmpty()) {
            return;
        }
        String frame = MeterMessage.format(reading);
        for (Subscriber subscriber : subscribers) {
            subscriber.offer(frame);
        }
        published.incrementAndGet();
    }

    /**
     * Offers an alert to every subscriber, on the same connection as the readings.
     *
     * <p>Alerts go out as {@code ALT} frames through the same per-subscriber outbox the
     * readings use, which is what keeps them in order relative to the reading that caused
     * them: a dashboard sees the 264 V reading and then the spike it raised, rather than the
     * two racing on separate sockets.</p>
     *
     * <p>Alerts are rare and matter more than any single reading, so a full outbox drops them
     * exactly as it drops readings — a subscriber far enough behind to be losing frames is
     * one whose alert would arrive minutes late anyway, and the alert log will still have it
     * from the database. Never blocks and never throws.</p>
     *
     * @param event the alert to broadcast; must not be null
     * @throws NullPointerException if {@code event} is null
     */
    public void publishAlert(Event event) {
        Objects.requireNonNull(event, "event");
        if (subscribers.isEmpty()) {
            return;
        }
        String frame = MeterMessage.formatAlert(event);
        for (Subscriber subscriber : subscribers) {
            subscriber.offer(frame);
        }
        publishedAlerts.incrementAndGet();
    }

    /**
     * Installs what happens when a subscribed dashboard sends a command.
     *
     * <p>Without one, commands are answered with an error rather than ignored: a threshold
     * editor whose commit silently did nothing is worse than one that says it could not.</p>
     *
     * @param commandHandler the handler, or null to refuse commands again
     */
    public void setCommandHandler(CommandHandler commandHandler) {
        this.commandHandler = commandHandler;
    }

    /** @return how many dashboards are currently subscribed. */
    public int getSubscriberCount() {
        return subscribers.size();
    }

    /** @return readings offered to the subscribers since start-up. */
    public long getPublishedCount() {
        return published.get();
    }

    /** @return alerts offered to the subscribers since start-up. */
    public long getPublishedAlertCount() {
        return publishedAlerts.get();
    }

    /** @return frames dropped because a subscriber's outbox was full. */
    public long getDroppedFrameCount() {
        return droppedFrames.get();
    }

    /** @return the port the live feed listens on. */
    public int getPort() {
        return port;
    }

    /** Stops accepting, disconnects every subscriber, and closes the port. */
    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        closeQuietly(listener);
        for (Subscriber subscriber : subscribers) {
            subscriber.disconnect();
        }
        if (acceptThread != null) {
            try {
                acceptThread.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Accepts dashboards and hands each to its own subscriber thread, handshake included. */
    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = listener.accept();
            } catch (IOException e) {
                if (running) {
                    System.err.println("[publisher] accept failed: " + e.getMessage());
                }
                return;
            }
            new Subscriber(socket, connectionCounter.incrementAndGet()).start();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            // Closing on the way out; there is nothing left to recover.
        }
    }

    /**
     * One connected dashboard: its socket, a bounded outbox, and the thread that drains the
     * outbox into the socket.
     */
    private final class Subscriber {

        private final Socket socket;
        private final String name;
        private final BlockingQueue<String> outbox = new ArrayBlockingQueue<>(subscriberQueueCapacity);
        private final Thread thread;
        private volatile boolean connected = true;

        Subscriber(Socket socket, int id) {
            this.socket = socket;
            this.name = "dashboard-" + id + " (" + socket.getRemoteSocketAddress() + ")";
            this.thread = new Thread(this::run, "dashboard-" + id);
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        /** Queues a frame, counting it as dropped if this subscriber is too far behind. */
        void offer(String frame) {
            if (!connected) {
                return;
            }
            if (!outbox.offer(frame)) {
                droppedFrames.incrementAndGet();
            }
        }

        void disconnect() {
            connected = false;
            closeQuietly(socket);
        }

        private void run() {
            try (Socket managed = socket;
                 BufferedReader in = new BufferedReader(new InputStreamReader(
                         managed.getInputStream(), StandardCharsets.US_ASCII));
                 Writer out = new BufferedWriter(new OutputStreamWriter(
                         managed.getOutputStream(), StandardCharsets.US_ASCII))) {

                if (!handshake(managed, in, out)) {
                    return;
                }

                subscribers.add(this);
                System.out.println("[publisher] " + name + " subscribed ("
                        + subscribers.size() + " subscriber(s))");

                // The commands a dashboard may send are read on their own thread: this one is
                // about to block in pump(), and a socket cannot be read by the thread that is
                // parked writing to it.
                Thread commands = new Thread(() -> readCommands(in), thread.getName() + "-cmd");
                commands.setDaemon(true);
                commands.start();

                try {
                    pump(out);
                } finally {
                    subscribers.remove(this);
                    commands.interrupt();
                    System.out.println("[publisher] " + name + " disconnected ("
                            + subscribers.size() + " subscriber(s))");
                }
            } catch (IOException e) {
                if (running && connected) {
                    System.err.println("[publisher] " + name + " failed: " + e.getMessage());
                }
            } finally {
                connected = false;
            }
        }

        /** Reads the subscribe line and answers it. Returns false if the client said something else. */
        private boolean handshake(Socket managed, BufferedReader in, Writer out) throws IOException {
            managed.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            String request;
            try {
                request = in.readLine();
            } catch (SocketException | java.net.SocketTimeoutException e) {
                System.err.println("[publisher] " + name + " sent no handshake within "
                        + HANDSHAKE_TIMEOUT_MS + "ms");
                return false;
            }
            if (request == null || !MeterMessage.SUBSCRIBE_COMMAND.equals(request.trim())) {
                System.err.println("[publisher] " + name + " sent '" + request + "', expected '"
                        + MeterMessage.SUBSCRIBE_COMMAND + "'");
                return false;
            }
            managed.setSoTimeout(0);
            managed.setTcpNoDelay(true);
            out.write(MeterMessage.SUBSCRIBE_ACK);
            out.write(MeterMessage.TERMINATOR);
            out.flush();
            return true;
        }

        /**
         * Reads command lines from a subscribed dashboard until the connection ends.
         *
         * <p>The reply goes back through the outbox rather than straight to the socket, so
         * that this thread and the pump thread never write to the same stream at once. It
         * therefore obeys the same drop-when-behind rule as everything else on the feed: a
         * dashboard far enough behind to be losing readings may lose its acknowledgement too,
         * and will report the reload as unconfirmed rather than as done.</p>
         */
        private void readCommands(BufferedReader in) {
            try {
                String line;
                while (connected && running && (line = in.readLine()) != null) {
                    String command = line.trim();
                    if (command.isEmpty()) {
                        continue;
                    }
                    CommandHandler handler = commandHandler;
                    String reply;
                    if (handler == null) {
                        reply = MeterMessage.ERROR_PREFIX + "this server accepts no commands "
                                + "(it is running without persistence, so it has no thresholds "
                                + "to reload)";
                    } else {
                        try {
                            reply = handler.handle(command);
                        } catch (RuntimeException e) {
                            reply = MeterMessage.ERROR_PREFIX + e.getMessage();
                        }
                    }
                    System.out.println("[publisher] " + name + " sent '" + command + "' -> " + reply);
                    offer(reply + MeterMessage.TERMINATOR);
                }
            } catch (IOException e) {
                // The connection went away; the pump thread notices the same thing and both
                // ends of the subscriber are torn down there.
            }
        }

        /** Drains the outbox into the socket until the publisher stops or the write fails. */
        private void pump(Writer out) throws IOException {
            while (running && connected) {
                String frame;
                try {
                    frame = outbox.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (frame == null) {
                    continue;
                }
                out.write(frame);
                // Flushed per frame on purpose: this is a live feed, and a frame sitting in
                // a buffer waiting for company is a frame the operator is not seeing.
                out.flush();
            }
        }
    }
}
