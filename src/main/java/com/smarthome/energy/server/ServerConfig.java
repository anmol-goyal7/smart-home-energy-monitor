package com.smarthome.energy.server;

import com.smarthome.energy.db.ConnectionFactory;
import com.smarthome.energy.db.DataAccessException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * Typed view over the runtime configuration shared by every process in the system.
 *
 * <p>Loads {@code src/main/resources/db.properties} and exposes the meter ingest port, the
 * dashboard live-feed port, and the simulator's timing settings; the JDBC parameters in the
 * same file are read by {@link ConnectionFactory}, which this class hands out through
 * {@link #connectionFactory()}. Centralising configuration here keeps ports and credentials
 * out of the code and lets the same build run against different databases without
 * recompilation.</p>
 *
 * <h2>Why the simulator's settings live in the server's config class</h2>
 *
 * <p>{@code db.properties} already carries {@code simulator.interval.ms} and
 * {@code simulator.anomaly.probability}: one copied file is meant to configure the whole
 * stack, so a demo needs one thing set up rather than three. Rather than give the simulator
 * a second loader that reads the same file with the same override rules, it reads this one.
 * The class name is the scaffold's; what it actually is, is the typed view over that single
 * file.</p>
 *
 * <h2>Which settings may default and which may not</h2>
 *
 * <p>Every value here has a default, because a port and a tick interval are conventions
 * rather than secrets and a wrong guess fails immediately and visibly ("connection
 * refused"). The JDBC settings deliberately do not default — {@link ConnectionFactory}
 * refuses to invent a URL, because a silent fallback there means writing to the wrong
 * database and not finding out. The two rules differ because the cost of being wrong
 * differs.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals; Unit III — JDBC connection setup.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class ServerConfig {

    /** Classpath location of the configuration file, copied from {@code db.properties.example}. */
    private static final String CONFIG_RESOURCE = "/db.properties";

    /** Port the meter simulators connect to when nothing says otherwise. */
    public static final int DEFAULT_METER_PORT = 5060;

    /** Port the dashboard subscribes to for the live feed when nothing says otherwise. */
    public static final int DEFAULT_DASHBOARD_PORT = 5061;

    /** Milliseconds between readings from one simulated meter when nothing says otherwise. */
    public static final long DEFAULT_SIMULATOR_INTERVAL_MS = 1_000L;

    /** Probability that a generated reading is an injected anomaly when nothing says otherwise. */
    public static final double DEFAULT_ANOMALY_PROBABILITY = 0.02;

    private static final String KEY_METER_PORT = "server.meter.port";
    private static final String KEY_DASHBOARD_PORT = "server.dashboard.port";
    private static final String KEY_INTERVAL = "simulator.interval.ms";
    private static final String KEY_ANOMALY = "simulator.anomaly.probability";

    private final int meterPort;
    private final int dashboardPort;
    private final long simulatorIntervalMillis;
    private final double anomalyProbability;

    private ServerConfig(int meterPort, int dashboardPort, long simulatorIntervalMillis,
                         double anomalyProbability) {
        this.meterPort = requirePort(meterPort, KEY_METER_PORT);
        this.dashboardPort = requirePort(dashboardPort, KEY_DASHBOARD_PORT);
        if (simulatorIntervalMillis <= 0) {
            throw new IllegalArgumentException(KEY_INTERVAL + " must be positive, was "
                    + simulatorIntervalMillis);
        }
        if (anomalyProbability < 0.0 || anomalyProbability > 1.0) {
            throw new IllegalArgumentException(KEY_ANOMALY + " must be within [0,1], was "
                    + anomalyProbability);
        }
        this.simulatorIntervalMillis = simulatorIntervalMillis;
        this.anomalyProbability = anomalyProbability;
    }

    /**
     * Reads the configuration file from the classpath, letting environment variables
     * override it and falling back to the defaults above for anything neither supplies.
     *
     * <p>The override convention is {@link ConnectionFactory}'s: the property name
     * upper-cased with dots replaced by underscores, so {@code server.meter.port} is
     * overridden by {@code SERVER_METER_PORT}. The lookup is repeated here rather than
     * shared because the two classes disagree about the important part — what to do when a
     * setting is missing — and that difference is the whole design.</p>
     *
     * @return the configuration
     * @throws IllegalArgumentException if a value is present but not usable (an unparseable
     *                                  or out-of-range port, interval, or probability)
     */
    public static ServerConfig load() {
        Properties props = loadProperties();
        return new ServerConfig(
                intValue(props, KEY_METER_PORT, DEFAULT_METER_PORT),
                intValue(props, KEY_DASHBOARD_PORT, DEFAULT_DASHBOARD_PORT),
                longValue(props, KEY_INTERVAL, DEFAULT_SIMULATOR_INTERVAL_MS),
                doubleValue(props, KEY_ANOMALY, DEFAULT_ANOMALY_PROBABILITY));
    }

    /** @return the port the meter simulators connect to. */
    public int getMeterPort() {
        return meterPort;
    }

    /** @return the port the dashboard subscribes to for the live feed. */
    public int getDashboardPort() {
        return dashboardPort;
    }

    /** @return milliseconds between readings from one simulated meter. */
    public long getSimulatorIntervalMillis() {
        return simulatorIntervalMillis;
    }

    /** @return the probability, in {@code [0,1]}, that a reading is an injected anomaly. */
    public double getAnomalyProbability() {
        return anomalyProbability;
    }

    /**
     * @param port the meter port to use instead of the configured one
     * @return a copy carrying that port, for a command-line override
     * @throws IllegalArgumentException if the port is outside {@code 1..65535}
     */
    public ServerConfig withMeterPort(int port) {
        return new ServerConfig(port, dashboardPort, simulatorIntervalMillis, anomalyProbability);
    }

    /**
     * @param port the dashboard port to use instead of the configured one
     * @return a copy carrying that port, for a command-line override
     * @throws IllegalArgumentException if the port is outside {@code 1..65535}
     */
    public ServerConfig withDashboardPort(int port) {
        return new ServerConfig(meterPort, port, simulatorIntervalMillis, anomalyProbability);
    }

    /**
     * @param intervalMillis milliseconds between readings, overriding the configured value
     * @return a copy carrying that interval
     * @throws IllegalArgumentException if the interval is not positive
     */
    public ServerConfig withSimulatorIntervalMillis(long intervalMillis) {
        return new ServerConfig(meterPort, dashboardPort, intervalMillis, anomalyProbability);
    }

    /**
     * @param probability anomaly probability in {@code [0,1]}, overriding the configured value
     * @return a copy carrying that probability
     * @throws IllegalArgumentException if the probability is outside {@code [0,1]}
     */
    public ServerConfig withAnomalyProbability(double probability) {
        return new ServerConfig(meterPort, dashboardPort, simulatorIntervalMillis, probability);
    }

    /**
     * Builds the JDBC connection factory from the same file.
     *
     * @return a factory for the configured database
     * @throws DataAccessException if a required JDBC setting is missing from both the file
     *                             and the environment
     */
    public ConnectionFactory connectionFactory() {
        return ConnectionFactory.fromDefaultConfig();
    }

    @Override
    public String toString() {
        return "ServerConfig[meterPort=" + meterPort
                + ", dashboardPort=" + dashboardPort
                + ", simulatorInterval=" + simulatorIntervalMillis + "ms"
                + ", anomalyProbability=" + anomalyProbability + "]";
    }

    /** Loads the file if present; an absent file is fine, since everything here has a default. */
    private static Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream in = ServerConfig.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + CONFIG_RESOURCE
                    + " from the classpath: " + e.getMessage(), e);
        }
        return props;
    }

    /** Returns the environment override if set, else the file value, else null. */
    private static String resolve(Properties props, String key) {
        String fromEnv = System.getenv(key.replace('.', '_').toUpperCase(Locale.ROOT));
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return props.getProperty(key);
    }

    private static int intValue(Properties props, String key, int fallback) {
        String raw = resolve(props, key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number, was '" + raw + "'", e);
        }
    }

    private static long longValue(Properties props, String key, long fallback) {
        String raw = resolve(props, key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number, was '" + raw + "'", e);
        }
    }

    private static double doubleValue(Properties props, String key, double fallback) {
        String raw = resolve(props, key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, was '" + raw + "'", e);
        }
    }

    private static int requirePort(int port, String key) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(key + " must be within 1..65535, was " + port);
        }
        return port;
    }
}
