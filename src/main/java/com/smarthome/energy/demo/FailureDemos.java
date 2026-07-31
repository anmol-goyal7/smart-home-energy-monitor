package com.smarthome.energy.demo;

import com.smarthome.energy.db.ConnectionFactory;
import com.smarthome.energy.db.DataAccessException;

import java.util.List;
import java.util.Locale;

/**
 * Entry point for the failure-mode demonstrations: four defects, each beside its correction.
 *
 * <pre>
 *   ./scripts/demo.sh --failure lost-update
 *   mvn exec:java -Dexec.mainClass=com.smarthome.energy.demo.FailureDemos -Dexec.args="--all"
 * </pre>
 *
 * <p>Each demonstration runs the broken version and the corrected version of the same
 * operation and prints both results, because a fix is only interesting next to the thing it
 * fixes. Three of the four need MySQL; {@code lost-update} is pure concurrency and needs
 * nothing, and {@code frozen-ui} additionally needs a display.</p>
 *
 * <table>
 *   <caption>The four demonstrations</caption>
 *   <tr><th>Name</th><th>Broken</th><th>Corrected</th><th>Unit</th></tr>
 *   <tr><td>{@code lost-update}</td><td>{@code count++} from many threads</td>
 *       <td>{@code AtomicLong}</td><td>I</td></tr>
 *   <tr><td>{@code frozen-ui}</td><td>a query on the event dispatch thread</td>
 *       <td>the same query on a {@code SwingWorker}</td><td>II</td></tr>
 *   <tr><td>{@code sql-injection}</td><td>a concatenated {@code WHERE}</td>
 *       <td>a bound {@code PreparedStatement}</td><td>III</td></tr>
 *   <tr><td>{@code partial-write}</td><td>autocommit across two inserts</td>
 *       <td>one transaction, rolled back</td><td>III</td></tr>
 * </table>
 *
 * <p>Every demonstration that writes cleans up after itself, and the two that touch tables
 * use their own sentinel time window or their own throwaway table, so running these against
 * the demonstration database cannot corrupt the history the analytics report on.</p>
 *
 * <p>Syllabus mapping: Units I, II, and III — see the table.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class FailureDemos {

    /** One demonstration: its name, what it shows, and whether it needs a database. */
    private record Demo(String name, String summary, boolean needsDatabase, boolean needsDisplay) {
    }

    private static final List<Demo> DEMOS = List.of(
            new Demo("lost-update",
                    "unsynchronised dispatcher counters lose increments under concurrent handlers",
                    false, false),
            new Demo("frozen-ui",
                    "a database query on the event dispatch thread stops the window repainting",
                    true, true),
            new Demo("sql-injection",
                    "a concatenated device-name lookup returns rows the caller never asked for",
                    true, false),
            new Demo("partial-write",
                    "a reading committed before its event fails leaves an orphan behind",
                    true, false));

    private FailureDemos() {
        // Entry point only.
    }

    /**
     * Runs one demonstration, or all of them.
     *
     * @param args {@code --demo NAME}, {@code --all}, {@code --list}, {@code --help}
     */
    public static void main(String[] args) {
        String requested = null;
        boolean all = false;

        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--demo" -> requested = value(args, ++i);
                    case "--all" -> all = true;
                    case "--list" -> {
                        printList();
                        return;
                    }
                    case "--help", "-h" -> {
                        printUsage();
                        return;
                    }
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("[demo] " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        if (!all && requested == null) {
            printUsage();
            System.exit(2);
            return;
        }

        List<Demo> toRun = all ? DEMOS : List.of(find(requested));
        int failed = 0;
        for (Demo demo : toRun) {
            if (!runOne(demo)) {
                failed++;
            }
        }

        if (failed > 0) {
            System.out.println();
            System.out.println("[demo] " + failed + " demonstration(s) did not produce the expected "
                    + "contrast. That is a result too — read the output above rather than the "
                    + "exit status.");
        }
    }

    /** Runs one demonstration inside its banner. @return whether it showed what it claims to */
    private static boolean runOne(Demo demo) {
        banner(demo);

        if (demo.needsDatabase()) {
            ConnectionFactory connections;
            try {
                connections = ConnectionFactory.fromDefaultConfig();
                // Fail here, with an actionable message, rather than half way through a
                // demonstration in front of an audience.
                connections.getConnection().close();
            } catch (DataAccessException | java.sql.SQLException e) {
                System.out.println("This demonstration needs MySQL, which is not reachable: "
                        + e.getMessage());
                System.out.println("Start it with `docker compose up -d` and copy "
                        + "src/main/resources/db.properties.example to db.properties.");
                return false;
            }
            return switch (demo.name()) {
                case "frozen-ui" -> FrozenUiDemo.run(connections);
                case "sql-injection" -> SqlInjectionDemo.run(connections);
                case "partial-write" -> PartialWriteDemo.run(connections);
                default -> throw new IllegalStateException("no such demonstration: " + demo.name());
            };
        }
        return LostUpdateDemo.run();
    }

    private static void banner(Demo demo) {
        String rule = "=".repeat(78);
        System.out.println();
        System.out.println(rule);
        System.out.println("  " + demo.name().toUpperCase(Locale.ROOT) + " — " + demo.summary());
        System.out.println(rule);
        System.out.println();
    }

    private static Demo find(String name) {
        for (Demo demo : DEMOS) {
            if (demo.name().equalsIgnoreCase(name.trim())) {
                return demo;
            }
        }
        throw new IllegalArgumentException("unknown demonstration '" + name + "'; available: "
                + DEMOS.stream().map(Demo::name).toList());
    }

    private static void printList() {
        System.out.println("Available failure-mode demonstrations:");
        for (Demo demo : DEMOS) {
            String needs = demo.needsDisplay() ? " [needs MySQL and a display]"
                    : demo.needsDatabase() ? " [needs MySQL]" : "";
            System.out.println("  " + demo.name() + " — " + demo.summary() + needs);
        }
    }

    private static String value(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException("option " + args[index - 1] + " needs a value");
        }
        return args[index];
    }

    private static void printUsage() {
        System.out.println("""
                Usage: FailureDemos [options]

                  --demo NAME   run one demonstration (see --list)
                  --all         run every demonstration in order
                  --list        list the demonstrations and what each one needs
                  --help        show this message
                """);
    }
}
