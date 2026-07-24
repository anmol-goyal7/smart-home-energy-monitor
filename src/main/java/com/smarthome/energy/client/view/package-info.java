/**
 * The <em>View</em> layer of the dashboard MVC: the Swing window and its panels
 * (per-appliance tiles, the history chart, and the event/alert log).
 *
 * <p>Views observe the model and render on the event dispatch thread; they hold no business
 * logic and never touch the network or database directly.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming with Swing/AWT.</p>
 */
package com.smarthome.energy.client.view;
