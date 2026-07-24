package com.smarthome.energy.client;

/**
 * TCP client that subscribes to the server's live reading feed.
 *
 * <p>Connects to the server's dashboard live-feed port, sends the subscribe handshake, and
 * then reads streamed readings on a background thread. Each reading is handed to the
 * controller, which updates the model; the view repaints on the Swing event dispatch
 * thread. Historical data is fetched separately by {@link HistoryQueryService}; this class
 * carries only the real-time stream.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming (background networking off the EDT);
 * Unit I — TCP client sockets.</p>
 *
 * @author Team member 2 (Swing MVC client)
 */
public final class LiveFeedClient {
    // Placeholder — subscribe handshake and read loop implemented by the author.
}
