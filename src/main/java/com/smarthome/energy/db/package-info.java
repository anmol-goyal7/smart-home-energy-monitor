/**
 * The JDBC persistence layer: a single connection factory plus one DAO per table
 * (devices, readings, events, thresholds).
 *
 * <p>All SQL lives here behind DAO methods, so the rest of the system persists and queries
 * domain objects without knowing any SQL or connection details.</p>
 *
 * <p>Syllabus mapping: Unit III — Database connectivity via JDBC.</p>
 */
package com.smarthome.energy.db;
