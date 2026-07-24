package com.smarthome.energy.server;

/**
 * Fan-out point that routes every validated {@code Reading} to its consumers.
 *
 * <p>This is the seam that keeps ingest fast and decoupled. A {@link ClientHandler} does
 * not know about the database, the rule engine, or the dashboard; it only calls
 * {@code dispatch(reading)}. The dispatcher is responsible for:
 * <ol>
 *   <li>persisting the reading through {@code ReadingDao};</li>
 *   <li>submitting it to the {@code RuleEngine} for power-quality evaluation
 *       (off the socket read path — see {@code docs/DESIGN.md});</li>
 *   <li>publishing it to live dashboard subscribers via {@link DashboardPublisher}.</li>
 * </ol>
 *
 * <p>Keeping the rule evaluation off the handler's read loop means a burst of anomalies
 * cannot slow down message ingestion from the meters.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals, concurrency (producer/consumer).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class ReadingDispatcher {
    // Placeholder — dispatch fan-out and worker queue implemented by the author.
}
