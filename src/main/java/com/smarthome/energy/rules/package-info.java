/**
 * The rule-based power-quality detection engine: the rule strategy interface, the engine
 * that applies every rule to each reading, the evaluation context, and the concrete rules
 * (voltage spike, voltage sag, load overload).
 *
 * <p>Rules are pure functions over a reading and a cached threshold context, so the engine
 * is open to new alert types without changes to the ingest path.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (strategy pattern).</p>
 */
package com.smarthome.energy.rules;
