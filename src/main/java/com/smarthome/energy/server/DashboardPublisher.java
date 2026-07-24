package com.smarthome.energy.server;

/**
 * Broadcasts live readings to connected dashboard subscribers.
 *
 * <p>The Swing dashboard connects to the server on the live-feed port and identifies
 * itself with a subscribe handshake. This publisher keeps the set of subscriber sockets
 * and, each time the {@link ReadingDispatcher} hands it a reading, writes that reading to
 * every subscriber. Historical data is served separately by the dashboard's own JDBC
 * queries; this class carries only the real-time stream.</p>
 *
 * <p>The subscriber set is shared across handler threads, so its implementation must be
 * thread-safe (e.g. a {@code CopyOnWriteArraySet}). Dead subscriber sockets are pruned on
 * write failure.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals, threading, socket I/O.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class DashboardPublisher {
    // Placeholder — subscriber registry and broadcast implemented by the author.
}
