package com.smarthome.energy.demo;

import com.smarthome.energy.db.ConnectionFactory;
import com.smarthome.energy.db.DataAccessException;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unit II: the same query run on the event dispatch thread and on a {@code SwingWorker}.
 *
 * <p>{@code DashboardController} runs every database read inside a {@code SwingWorker}. The
 * usual justification — "otherwise the UI freezes" — is easy to nod at and hard to picture,
 * because a frozen window looks exactly like a slow one until you try to click something.
 * This demonstration puts a number on it.</p>
 *
 * <h2>How the freeze is measured</h2>
 *
 * <p>A {@code javax.swing.Timer} ticking every 50 ms is, by construction, a thing that can
 * only run on the event dispatch thread. So the longest gap between two of its ticks is
 * exactly how long the EDT was unavailable — to that timer, and equally to every repaint,
 * button press, and window drag that arrived in the meantime. A spinner painted from the same
 * timer makes it visible as well as measurable: it stops dead during the blocking run and
 * keeps turning during the worker run.</p>
 *
 * <p>The query is {@code SELECT SLEEP(n)}, which is not a query anybody would write. It is
 * used because the point being demonstrated is not what a query costs but which thread pays
 * for it, and a deliberately timed sleep makes the two runs comparable to the millisecond
 * instead of depending on how much history happens to be stored. The real
 * {@code loadHistory} on a large {@code readings} table blocks the same thread for the same
 * reason.</p>
 *
 * <p>This is the one demonstration that needs a display, and it says so rather than throwing
 * a {@code HeadlessException} out of a script.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming with Swing/AWT (the EDT, SwingWorker).</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
final class FrozenUiDemo {

    /** How long the demonstration query takes the database to answer, in seconds. */
    private static final int QUERY_SECONDS = 3;

    /** Interval of the timer whose missed ticks measure the freeze. */
    private static final int TICK_MS = 50;

    /** A gap longer than this is a stall rather than scheduling noise. */
    private static final long STALL_THRESHOLD_MS = 250;

    private FrozenUiDemo() {
        // Static entry point only.
    }

    /**
     * Opens a small window, runs the query both ways, and reports the longest EDT stall each
     * one caused.
     *
     * @param connections where the connection comes from; must not be null
     * @return true if the demonstration behaved as expected: the blocking run stalled the EDT
     *         and the worker run did not
     */
    static boolean run(ConnectionFactory connections) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("This demonstration needs a display: it is about a window that stops");
            System.out.println("repainting, and there is no window here. Run it on a desktop session.");
            System.out.println("The other three demonstrations are headless.");
            return false;
        }

        Harness harness = new Harness();
        SwingUtilities.invokeLater(harness::open);
        harness.awaitOpen();

        System.out.println("A " + QUERY_SECONDS + "-second query, run two ways, while a " + TICK_MS
                + " ms timer ticks on the EDT.");
        System.out.println("The longest gap between ticks is how long the window was unresponsive.");
        System.out.println();

        harness.setCaption("BROKEN — querying on the event dispatch thread");
        long blockingStall = harness.measure(() -> SwingUtilities.invokeLater(() -> {
            // The defect, exactly: a blocking JDBC call straight from an EDT task, which is
            // what an action listener that "just runs the query" produces.
            slowQuery(connections);
            harness.finishRun();
        }));

        harness.setCaption("CORRECTED — querying on a SwingWorker");
        long workerStall = harness.measure(() -> SwingUtilities.invokeLater(() ->
                new SwingWorker<Void, Void>() {
                    @Override
                    protected Void doInBackground() {
                        slowQuery(connections);
                        return null;
                    }

                    @Override
                    protected void done() {
                        harness.finishRun();
                    }
                }.execute()));

        SwingUtilities.invokeLater(harness::close);

        System.out.printf(Locale.ROOT,
                "  BROKEN     query on the EDT          longest gap between ticks: %,5d ms%n",
                blockingStall);
        System.out.printf(Locale.ROOT,
                "  CORRECTED  query on a SwingWorker    longest gap between ticks: %,5d ms%n",
                workerStall);
        System.out.println();
        System.out.println("The blocking run's gap is the query's duration, because the EDT spent it");
        System.out.println("inside JDBC. Nothing repainted, no click was processed, and the window");
        System.out.println("manager would have offered to kill the application. The worker run's gap");
        System.out.println("is the timer's own interval plus scheduling noise: the query took just as");
        System.out.println("long, on a thread whose job is to wait.");

        return blockingStall > STALL_THRESHOLD_MS && workerStall <= STALL_THRESHOLD_MS;
    }

    /**
     * A query the database takes {@link #QUERY_SECONDS} to answer.
     *
     * <p>Bound rather than concatenated even here, in a demonstration where it could not
     * matter, because the moment a project keeps one unbound statement "just for a test" is
     * the moment it has an unbound statement.</p>
     */
    private static void slowQuery(ConnectionFactory connections) {
        try (Connection connection = connections.getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT SLEEP(?)")) {
            ps.setInt(1, QUERY_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new DataAccessException("the frozen-UI demonstration's query failed", e);
        }
    }

    /** The window, its ticking timer, and the stall measurement. */
    private static final class Harness {

        private final JFrame frame = new JFrame("Frozen UI demonstration");
        private final JLabel caption = new JLabel(" ");
        private final SpinnerPanel spinner = new SpinnerPanel();

        private final CountDownLatch opened = new CountDownLatch(1);
        private final AtomicLong longestGapMillis = new AtomicLong();
        private final AtomicLong lastTickNanos = new AtomicLong();

        private volatile CountDownLatch runFinished;
        private Timer timer;

        void open() {
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            JPanel root = new JPanel(new BorderLayout(0, 8));
            root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            root.setBackground(Color.WHITE);
            caption.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            root.add(caption, BorderLayout.NORTH);
            root.add(spinner, BorderLayout.CENTER);
            root.add(new JLabel("The spinner turns only while the event dispatch thread is free."),
                    BorderLayout.SOUTH);
            frame.setContentPane(root);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            lastTickNanos.set(System.nanoTime());
            timer = new Timer(TICK_MS, e -> tick());
            timer.start();
            opened.countDown();
        }

        void close() {
            if (timer != null) {
                timer.stop();
            }
            frame.dispose();
        }

        void awaitOpen() {
            await(opened, 10);
        }

        void setCaption(String text) {
            SwingUtilities.invokeLater(() -> caption.setText(text));
        }

        /** Records the gap since the previous tick and turns the spinner. */
        private void tick() {
            long now = System.nanoTime();
            long gapMillis = TimeUnit.NANOSECONDS.toMillis(now - lastTickNanos.getAndSet(now));
            longestGapMillis.accumulateAndGet(gapMillis, Math::max);
            spinner.advance();
        }

        /**
         * Runs one variant and returns the longest EDT gap it caused.
         *
         * @param start submits the work; must arrange for {@link #finishRun()} to be called
         */
        long measure(Runnable start) {
            runFinished = new CountDownLatch(1);
            longestGapMillis.set(0);
            lastTickNanos.set(System.nanoTime());

            start.run();
            await(runFinished, QUERY_SECONDS + 15L);

            // One extra tick's grace: the gap that spans the end of a blocking run is only
            // recorded when the timer next fires, which is up to TICK_MS after the query
            // returned.
            sleepQuietly(TICK_MS * 3L);
            return longestGapMillis.get();
        }

        void finishRun() {
            CountDownLatch latch = runFinished;
            if (latch != null) {
                latch.countDown();
            }
        }

        private static void await(CountDownLatch latch, long seconds) {
            try {
                latch.await(seconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A rotating arc: the cheapest possible thing that stops when the EDT does. */
    private static final class SpinnerPanel extends JPanel {

        private static final long serialVersionUID = 1L;

        private int angle;

        SpinnerPanel() {
            setPreferredSize(new Dimension(320, 120));
            setBackground(Color.WHITE);
        }

        void advance() {
            angle = (angle + 12) % 360;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int size = Math.min(getWidth(), getHeight()) - 40;
                int x = (getWidth() - size) / 2;
                int y = (getHeight() - size) / 2;
                g.setColor(new Color(0xDD, 0xDD, 0xDD));
                g.drawOval(x, y, size, size);
                g.setColor(new Color(0x21, 0x96, 0xF3));
                g.drawArc(x, y, size, size, angle, 90);
            } finally {
                g.dispose();
            }
        }
    }
}
