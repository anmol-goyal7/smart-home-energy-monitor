package com.smarthome.energy.db;

/**
 * Central factory for JDBC {@code Connection}s to the MySQL database.
 *
 * <p>Reads the JDBC URL, user, and password from configuration and hands out connections
 * to the DAOs. Concentrating connection creation in one place means the driver class, URL
 * format, and credential source are defined exactly once, and it is the natural place to
 * introduce a connection pool later without touching any DAO.</p>
 *
 * <p>Intended API:
 * <ul>
 *   <li>{@code Connection getConnection()} — an open connection to
 *       {@code jdbc:mysql://.../smart_home_energy}.</li>
 * </ul>
 *
 * <p>Syllabus mapping: Unit III — Database connectivity via JDBC.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class ConnectionFactory {
    // Placeholder — driver loading and getConnection() implemented by the author.
}
