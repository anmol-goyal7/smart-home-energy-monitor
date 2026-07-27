package com.smarthome.energy.client.model;

/**
 * The <em>Model</em> of the dashboard's MVC structure: the observable application state.
 *
 * <p>Holds the current {@link ApplianceState} for every device plus a rolling window of
 * recent readings and events. It exposes change notifications (listener callbacks) that the
 * view subscribes to, so the UI never reaches into raw data — it only reacts to model
 * updates. The controller mutates this model; the view reads it.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming with Swing/AWT (MVC, observer pattern).</p>
 *
 * @author Bhumika Rajput (Swing MVC client)
 */
public final class DashboardModel {
    // Placeholder — state stores and listener notification implemented by the author.
}
