package com.smarthome.energy.demo;

import com.smarthome.energy.db.ConnectionFactory;
import com.smarthome.energy.db.DataAccessException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit III: a device-name lookup built by string concatenation, and the same lookup bound.
 *
 * <p>Every DAO in this project uses {@code PreparedStatement} and binds its parameters. The
 * reason is usually given as "SQL injection", which is a phrase rather than a demonstration.
 * This runs both versions of one lookup against a throwaway table and shows the concatenated
 * one returning rows the caller had no right to.</p>
 *
 * <h2>Why a throwaway table</h2>
 *
 * <p>The exploit needs a table it is allowed to abuse. Running it against {@code devices}
 * would work identically and would mean a demonstration that reads the real catalogue with a
 * crafted string — harmless here, and precisely the habit that makes a demonstration
 * dangerous the day the schema is not a toy. The table is created, used, and dropped inside
 * this method.</p>
 *
 * <h2>What the exploit does and does not show</h2>
 *
 * <p>The classic follow-up — {@code '; DROP TABLE …} — does not fire here, because
 * Connector/J refuses multiple statements in one {@code execute} unless
 * {@code allowMultiQueries} is turned on, which this project's URL does not do. That is worth
 * saying out loud rather than quietly demonstrating something weaker: the driver's default
 * removes the most spectacular consequence, and leaves the ordinary one, which is that an
 * attacker chooses what the {@code WHERE} clause means. Reading every row of a table you were
 * meant to see one row of is a data breach whether or not anything was dropped.</p>
 *
 * <p>Syllabus mapping: Unit III — JDBC (PreparedStatement, parameter binding, injection).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
final class SqlInjectionDemo {

    /** Table created for this demonstration and dropped when it finishes. */
    private static final String TABLE = "demo_injection_targets";

    /** The input a well-behaved caller supplies. */
    private static final String HONEST_INPUT = "Kitchen Refrigerator";

    /** The input an attacker supplies: a name that matches nothing, plus a tautology. */
    private static final String HOSTILE_INPUT = "no such device' OR '1'='1";

    private SqlInjectionDemo() {
        // Static entry point only.
    }

    /**
     * Sets up the throwaway table, runs both lookups with both inputs, and reports.
     *
     * @param connections where the connection comes from; must not be null
     * @return true if the demonstration behaved as expected: the concatenated lookup leaked
     *         rows and the bound one did not
     */
    static boolean run(ConnectionFactory connections) {
        try (Connection connection = connections.getConnection()) {
            createTable(connection);
            try {
                System.out.println("A lookup that should return exactly one row, run two ways.");
                System.out.println();

                System.out.println("  honest input:  " + HONEST_INPUT);
                int concatenatedHonest = concatenated(connection, HONEST_INPUT).size();
                int boundHonest = bound(connection, HONEST_INPUT).size();
                System.out.println("    BROKEN     string-concatenated  " + concatenatedHonest + " row(s)");
                System.out.println("    CORRECTED  PreparedStatement    " + boundHonest + " row(s)");
                System.out.println("    Both correct. This is why the mistake survives code review.");
                System.out.println();

                System.out.println("  hostile input: " + HOSTILE_INPUT);
                List<String> leaked = concatenated(connection, HOSTILE_INPUT);
                List<String> refused = bound(connection, HOSTILE_INPUT);
                System.out.println("    BROKEN     string-concatenated  " + leaked.size()
                        + " row(s) — " + leaked);
                System.out.println("    CORRECTED  PreparedStatement    " + refused.size()
                        + " row(s) — the whole string is one name, and no device is called that");
                System.out.println();

                System.out.println("  the SQL each one actually sent:");
                System.out.println("    BROKEN     " + concatenatedSql(HOSTILE_INPUT));
                System.out.println("    CORRECTED  " + boundSql() + "   [1] = " + HOSTILE_INPUT);
                System.out.println();
                System.out.println("The bound version is not escaping the quote. The value never");
                System.out.println("becomes part of the statement at all — the server parses the SQL");
                System.out.println("first and is handed the parameter afterwards, so there is no");
                System.out.println("arrangement of characters that can turn into syntax.");

                return leaked.size() > 1 && refused.isEmpty();
            } finally {
                dropTable(connection);
            }
        } catch (SQLException e) {
            throw new DataAccessException("the injection demonstration could not run", e);
        }
    }

    /** The lookup as it must not be written. */
    private static List<String> concatenated(Connection connection, String name) throws SQLException {
        List<String> found = new ArrayList<>();
        // Statement, not PreparedStatement, and the caller's string spliced straight into the
        // text. This is the defect, reproduced exactly.
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(concatenatedSql(name))) {
            while (rs.next()) {
                found.add(rs.getString("name"));
            }
        }
        return found;
    }

    /** The lookup as every DAO in this project writes it. */
    private static List<String> bound(Connection connection, String name) throws SQLException {
        List<String> found = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(boundSql())) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getString("name"));
                }
            }
        }
        return found;
    }

    private static String concatenatedSql(String name) {
        return "SELECT name FROM " + TABLE + " WHERE name = '" + name + "'";
    }

    private static String boundSql() {
        return "SELECT name FROM " + TABLE + " WHERE name = ?";
    }

    private static void createTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
            statement.execute("CREATE TABLE " + TABLE + " ("
                    + "id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                    + "name VARCHAR(64) NOT NULL, "
                    + "secret VARCHAR(64) NOT NULL) ENGINE = InnoDB");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (name, secret) VALUES (?, ?)")) {
            String[][] rows = {
                    {"Kitchen Refrigerator", "meter-key-1"},
                    {"Living Room HVAC", "meter-key-2"},
                    {"Washing Machine", "meter-key-3"},
                    {"Water Heater", "meter-key-4"},
                    {"Home Office Desktop", "meter-key-5"},
                    {"Network Router", "meter-key-6"},
            };
            for (String[] row : rows) {
                ps.setString(1, row[0]);
                ps.setString(2, row[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void dropTable(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
        } catch (SQLException e) {
            System.err.println("[demo] could not drop " + TABLE + ": " + e.getMessage());
        }
    }
}
