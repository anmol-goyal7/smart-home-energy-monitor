package com.smarthome.energy.server;

import com.smarthome.energy.model.Reading;
import com.smarthome.energy.protocol.MeterMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two accept strategies, and in particular the claim Evidence 1 rests on: that a
 * fixed pool does not multiplex its threads across more connections than it has, because a
 * {@code ClientHandler} holds its thread for the life of the connection.
 *
 * <p>These are real sockets on a loopback port rather than mocks. The behaviour under test is
 * "how many connections actually get read from", which is a property of threads blocking on
 * real streams; a fake handler that returned immediately would make both strategies look
 * identical and would be testing nothing.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
class AcceptStrategyTest {

    /** How long to wait for the strategy to get around to a connection before giving up. */
    private static final long SETTLE_MILLIS = 2_000L;

    @Test
    @DisplayName("thread-per-client reads from every connection at once")
    void threadPerClientServesEveryConnection() throws Exception {
        try (Fixture fixture = new Fixture(AcceptStrategy.threadPerClient())) {
            fixture.connect(6);
            fixture.sendOneReadingFromEach();

            assertEquals(6, fixture.awaitReadings(6),
                    "every meter should have been read from");
            assertEquals(6, fixture.strategy.getPeakThreadCount(),
                    "one thread per connection is the whole model");
        }
    }

    @Test
    @DisplayName("a fixed pool reads from only as many connections as it has threads")
    void poolStarvesConnectionsPastItsSize() throws Exception {
        try (Fixture fixture = new Fixture(AcceptStrategy.fixedPool(2))) {
            fixture.connect(6);
            fixture.sendOneReadingFromEach();

            // All six sockets are accepted by the OS and all six meters believe they are
            // connected. Only two are being read.
            int delivered = fixture.awaitReadings(6);
            assertEquals(2, delivered,
                    "a pool of 2 can only serve 2 blocking handlers; the other 4 connections "
                            + "are accepted and then ignored");
            assertEquals(2, fixture.strategy.getPeakThreadCount(),
                    "the pool bounds the thread count by bounding who gets served");
        }
    }

    @Test
    @DisplayName("a pool big enough for the fleet behaves exactly like thread-per-client")
    void poolLargeEnoughServesEveryone() throws Exception {
        try (Fixture fixture = new Fixture(AcceptStrategy.fixedPool(8))) {
            fixture.connect(6);
            fixture.sendOneReadingFromEach();

            assertEquals(6, fixture.awaitReadings(6),
                    "six meters through a pool of eight should all be served");
        }
    }

    @Test
    @DisplayName("--accept parses the strategies the server documents")
    void parsesTheDocumentedSpecs() {
        try (AcceptStrategy perClient = AcceptStrategy.parse("thread-per-client")) {
            assertEquals("thread-per-client", perClient.name());
        }
        try (AcceptStrategy defaultPool = AcceptStrategy.parse("pool")) {
            assertEquals("pool(8)", defaultPool.name());
        }
        try (AcceptStrategy sizedPool = AcceptStrategy.parse("pool:3")) {
            assertEquals("pool(3)", sizedPool.name());
        }
    }

    @Test
    @DisplayName("an unusable --accept value names what was expected")
    void refusesNonsenseSpecs() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> AcceptStrategy.parse("magic")).getMessage().contains("thread-per-client"));
        assertThrows(IllegalArgumentException.class, () -> AcceptStrategy.parse("pool:nine"));
        assertThrows(IllegalArgumentException.class, () -> AcceptStrategy.parse("pool:0"));
        assertThrows(IllegalArgumentException.class, () -> AcceptStrategy.parse(" "));
    }

    @Test
    @DisplayName("a closed strategy refuses further connections rather than dropping them silently")
    void closedStrategyRefusesToServe() throws Exception {
        try (Fixture fixture = new Fixture(AcceptStrategy.threadPerClient())) {
            fixture.strategy.close();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", fixture.port()), 2_000);
                ClientHandler handler = new ClientHandler(socket, 1, fixture.dispatcher,
                        deviceId -> true, h -> { });
                assertTrue(!fixture.strategy.serve(handler, 1),
                        "a closed strategy should say it did not take the connection");
            }
        }
    }

    /** A listener, a strategy, a dispatcher, and a counting sink — the ingest path, minimally. */
    private static final class Fixture implements AutoCloseable {

        private final AcceptStrategy strategy;
        private final ReadingDispatcher dispatcher;
        private final ServerSocket listener;
        private final Thread acceptThread;
        private final List<Socket> clients = new CopyOnWriteArrayList<>();
        private final AtomicInteger connectionIds = new AtomicInteger();
        private final AtomicInteger delivered = new AtomicInteger();

        private volatile boolean running = true;

        Fixture(AcceptStrategy strategy) throws IOException {
            this.strategy = strategy;
            this.dispatcher = new ReadingDispatcher(List.of(
                    ReadingDispatcher.Sink.of("test", reading -> delivered.incrementAndGet())));
            this.listener = new ServerSocket(0);
            this.acceptThread = new Thread(this::acceptLoop, "test-accept");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket socket = listener.accept();
                    int id = connectionIds.incrementAndGet();
                    strategy.serve(new ClientHandler(socket, id, dispatcher, deviceId -> true,
                            handler -> { }), id);
                } catch (IOException e) {
                    return;
                }
            }
        }

        /** Opens {@code count} client sockets and waits for the accept loop to have seen them. */
        void connect(int count) throws Exception {
            for (int i = 0; i < count; i++) {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress("127.0.0.1", port()), 2_000);
                socket.setTcpNoDelay(true);
                clients.add(socket);
            }
            long deadline = System.currentTimeMillis() + SETTLE_MILLIS;
            while (connectionIds.get() < count && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
        }

        /** Writes one well-formed frame down each client socket. */
        void sendOneReadingFromEach() throws IOException {
            for (int i = 0; i < clients.size(); i++) {
                String frame = MeterMessage.format(
                        Reading.fromEpochMillis(i + 1, 1_721_817_600_000L, 230.0, 4.35, 1000.5));
                OutputStream out = clients.get(i).getOutputStream();
                out.write(frame.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        }

        /**
         * Waits for {@code hoped} readings to reach the sink.
         *
         * <p>Returns early once they all arrive, and otherwise waits the full settling time
         * before reporting a shortfall — which is the case the pool test is asserting, so it
         * has to be a timeout rather than a snapshot taken too soon.</p>
         *
         * @return how many actually arrived within {@link #SETTLE_MILLIS}
         */
        int awaitReadings(int hoped) throws InterruptedException {
            long deadline = System.currentTimeMillis() + SETTLE_MILLIS;
            while (delivered.get() < hoped && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            return delivered.get();
        }

        @Override
        public void close() {
            running = false;
            clients.forEach(socket -> {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Test teardown.
                }
            });
            try {
                listener.close();
            } catch (IOException e) {
                // Test teardown.
            }
            strategy.close();
            dispatcher.close();
        }
    }
}
