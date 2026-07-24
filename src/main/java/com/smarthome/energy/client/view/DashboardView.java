package com.smarthome.energy.client.view;

/**
 * The <em>View</em> of the dashboard's MVC structure: the top-level Swing window.
 *
 * <p>Owns the {@code JFrame} and lays out the child panels — one {@link AppliancePanel}
 * per device, the {@link HistoryChartPanel}, and the {@link EventLogPanel}. The view
 * observes the {@code DashboardModel} and repaints on the Swing event dispatch thread when
 * the model changes; it holds no business logic and never talks to the network or database
 * directly.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming with Swing/AWT (containers, layout, EDT).</p>
 *
 * @author Team member 2 (Swing MVC client)
 */
public final class DashboardView {
    // Placeholder — frame construction, layout, and model-change rendering implemented by the author.
}
