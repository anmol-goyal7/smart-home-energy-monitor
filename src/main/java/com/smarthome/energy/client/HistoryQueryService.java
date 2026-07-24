package com.smarthome.energy.client;

/**
 * Read-only JDBC access used by the dashboard to populate history charts and the alert log.
 *
 * <p>Where {@link LiveFeedClient} supplies the real-time stream, this service answers the
 * "what happened before I opened the window" questions by querying the database directly
 * through the {@code ReadingDao} and {@code EventDao}. Keeping historical reads in their
 * own service leaves the live path untouched.</p>
 *
 * <p>Syllabus mapping: Unit III — Database connectivity via JDBC (read queries from the UI).</p>
 *
 * @author Team member 2 (Swing MVC client)
 */
public final class HistoryQueryService {
    // Placeholder — history/alert queries implemented by the author.
}
