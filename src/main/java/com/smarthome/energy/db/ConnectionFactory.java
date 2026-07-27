package com.smarthome.energy.db;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;

/**
 * Central factory for JDBC {@code Connection}s to the MySQL database.
 *
 * <p>Reads the JDBC URL, user, and password from configuration and hands out connections
 * to the DAOs. Concentrating connection creation in one place means the driver class, URL
 * format, and credential source are defined exactly once, and it is the natural place to
 * introduce a connection pool later without touching any DAO.</p>
 *
 * <p>API:
 * <ul>
 *   <li>{@link #getConnection()} — an open connection to
 *       {@code jdbc:mysql://.../smart_home_energy}.</li>
 * </ul>
 *
 * <h2>Where the settings come from</h2>
 *
 * <p>{@link #fromDefaultConfig()} reads {@code db.properties} from the classpath and then
 * lets environment variables override any key found there, so the same build runs against a
 * developer's local file and against a container that only has environment variables. The
 * variable name is the property name upper-cased with dots replaced by underscores, so
 * {@code jdbc.url} is overridden by {@code JDBC_URL}.</p>
 *
 * <p>The configuration file is deliberately not required: if it is absent but the
 * environment supplies the three values, the factory is happy. If neither source has them,
 * the error names the missing key and the copy step that fixes it, because a silent fallback
 * to a default URL is how a project ends up writing to the wrong database.</p>
 *
 * <p>This class currently opens a fresh connection per call. That is the honest starting
 * point for Phase 1 and the baseline the pooled variant is measured against in the JDBC
 * benchmark; because every DAO asks this class rather than {@code DriverManager}, adding a
 * pool later changes this file and nothing else.</p>
 *
 * <p>Syllabus mapping: Unit III — Database connectivity via JDBC.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class ConnectionFactory {

    /** Classpath location of the configuration file, copied from {@code db.properties.example}. */
    private static final String CONFIG_RESOURCE = "/db.properties";

    private static final String KEY_URL = "jdbc.url";
    private static final String KEY_USER = "jdbc.user";
    private static final String KEY_PASSWORD = "jdbc.password";
    private static final String KEY_DRIVER = "jdbc.driver";

    private final String url;
    private final String user;
    private final String password;

    /**
     * Creates a factory for an explicit set of credentials, bypassing configuration
     * discovery. Used by the tests and benchmarks, which point at their own database.
     *
     * @param url      JDBC URL; must not be null
     * @param user     database user; must not be null
     * @param password database password; must not be null
     * @throws NullPointerException if any argument is null
     */
    public ConnectionFactory(String url, String user, String password) {
        this.url = Objects.requireNonNull(url, "url");
        this.user = Objects.requireNonNull(user, "user");
        this.password = Objects.requireNonNull(password, "password");
    }

    /**
     * Creates a factory from {@code db.properties} on the classpath, with environment
     * variables taking precedence over the file.
     *
     * @return a factory configured for the local database
     * @throws DataAccessException if a required setting is missing from both sources, or if
     *                             the configured driver class cannot be loaded
     */
    public static ConnectionFactory fromDefaultConfig() {
        Properties props = loadProperties();

        String driver = resolve(props, KEY_DRIVER);
        if (driver != null && !driver.isBlank()) {
            // JDBC 4 drivers register themselves through the ServiceLoader, so this is not
            // strictly required with Connector/J on the classpath. It is kept because it
            // turns "no suitable driver" — which surfaces later, at connect time, and names
            // nothing useful — into an immediate, specific failure at start-up.
            try {
                Class.forName(driver);
            } catch (ClassNotFoundException e) {
                throw new DataAccessException("JDBC driver class not found on the classpath: " + driver
                        + " (is the mysql-connector-j dependency present?)");
            }
        }

        return new ConnectionFactory(
                require(props, KEY_URL),
                require(props, KEY_USER),
                require(props, KEY_PASSWORD));
    }

    /**
     * Opens a new connection to the configured database.
     *
     * <p>The caller owns the returned connection and must close it — every DAO does so with
     * try-with-resources.</p>
     *
     * @return an open connection
     * @throws SQLException if the database is unreachable or rejects the credentials
     */
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** @return the JDBC URL this factory connects to; useful in diagnostics. */
    public String getUrl() {
        return url;
    }

    /**
     * Loads the configuration file from the classpath, returning empty properties if it is
     * absent so that an environment-only configuration still works.
     */
    private static Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream in = ConnectionFactory.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new DataAccessException("failed to read " + CONFIG_RESOURCE + " from the classpath: "
                    + e.getMessage());
        }
        return props;
    }

    /** Returns the environment override if set, else the file value, else null. */
    private static String resolve(Properties props, String key) {
        String fromEnv = System.getenv(key.replace('.', '_').toUpperCase(java.util.Locale.ROOT));
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return props.getProperty(key);
    }

    /** As {@link #resolve}, but fails with an actionable message when the key is absent. */
    private static String require(Properties props, String key) {
        String value = resolve(props, key);
        if (value == null || value.isBlank()) {
            throw new DataAccessException("missing required setting '" + key + "'. Set it in "
                    + "src/main/resources/db.properties (cp src/main/resources/db.properties.example "
                    + "src/main/resources/db.properties) or supply the environment variable "
                    + key.replace('.', '_').toUpperCase(java.util.Locale.ROOT) + ".");
        }
        return value;
    }
}
