package com.smarthome.energy.server;

/**
 * Typed view over the server's runtime configuration.
 *
 * <p>Loads {@code src/main/resources/db.properties} (and any server settings) and exposes
 * the meter ingest port, the dashboard live-feed port, and JDBC connection parameters.
 * Centralising configuration here keeps ports and credentials out of the code and lets
 * the same build run against different databases without recompilation.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals; Unit III — JDBC connection setup.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class ServerConfig {
    // Placeholder — property loading and accessors implemented by the author.
}
