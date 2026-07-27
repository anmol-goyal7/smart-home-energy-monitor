package com.smarthome.energy.client.controller;

/**
 * The <em>Controller</em> of the dashboard's MVC structure.
 *
 * <p>Sits between the network/data layer and the model. It receives readings from the
 * {@code LiveFeedClient} and history from {@code HistoryQueryService}, translates them into
 * model updates ({@code DashboardModel}), and handles user gestures from the view (e.g.
 * selecting which appliance to chart). It ensures all model mutations that the view observes
 * happen on the Swing event dispatch thread.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming with Swing/AWT (MVC controller, event
 * handling, EDT marshalling).</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
public final class DashboardController {
    // Placeholder — feed handling, user-action handling, and model updates implemented by the author.
}
