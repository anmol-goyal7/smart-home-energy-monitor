package com.smarthome.energy.client.view;

import com.smarthome.energy.client.model.ApplianceState;
import com.smarthome.energy.model.Reading;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A scrolling strip chart of whole-home load over the last minute.
 *
 * <p>The tiles say what each appliance is doing now and the {@link HistoryChartPanel} says
 * what one appliance did over hours. Neither answers the question an operator actually opens
 * a dashboard with — "is the house drawing more than usual just now?" — because that is a
 * whole-home number over a window of seconds. This panel is that number: the sum of every
 * appliance's latest power, sampled once a second, scrolling right to left.</p>
 *
 * <h2>Sampled on a clock, not on arrival</h2>
 *
 * <p>The obvious implementation adds a point whenever a reading arrives. Six meters reporting
 * independently would then contribute six points per second, each of them the total at a
 * moment when only one appliance had just updated, and the line would carry a sawtooth that
 * is an artefact of the arrival pattern rather than anything about the house. Sampling the
 * whole model on a fixed tick — {@link #sample(List)}, driven by the view's one-second timer —
 * gives one point per second whatever the meters do, so the x-axis is time and the shape is
 * the load.</p>
 *
 * <p>A sample also records how many appliances were stale when it was taken, and stale
 * appliances are excluded from the total. A meter that stops reporting would otherwise hold
 * its last value in the sum forever, and the chart would show a house drawing steady power
 * from an appliance nobody can hear from. The count is drawn in the corner so a total that
 * has quietly gone incomplete says so.</p>
 *
 * <p>The buffer is a fixed-size ring: a window this panel never scrolls back through has no
 * reason to keep anything older than its left edge.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming with Swing/AWT (custom painting, timers).</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
public final class LiveChartPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Seconds of history the strip holds — one sample per second. */
    public static final int WINDOW_SECONDS = 60;

    private static final Dimension MINIMUM_SIZE = new Dimension(420, 160);
    private static final int MARGIN_LEFT = 58;
    private static final int MARGIN_RIGHT = 12;
    private static final int MARGIN_TOP = 24;
    private static final int MARGIN_BOTTOM = 22;
    private static final int Y_DIVISIONS = 4;

    private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private static final Font AXIS_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);

    /** Watts the y-axis covers until the load exceeds it, so an idle house is not a flat line at the top. */
    private static final double MINIMUM_SCALE_WATTS = 500.0;

    // A ring rather than a list: the window is fixed, so the oldest sample is overwritten
    // rather than shifted out, and painting costs the same whether the dashboard has been
    // open for a minute or a week.
    private final double[] watts = new double[WINDOW_SECONDS];
    private final int[] reporting = new int[WINDOW_SECONDS];
    private int nextIndex;
    private int sampleCount;

    private double peakSeen;
    private Instant lastSampleAt;

    /** Creates an empty strip chart. */
    public LiveChartPanel() {
        setPreferredSize(MINIMUM_SIZE);
        setMinimumSize(MINIMUM_SIZE);
        setOpaque(true);
        setBackground(DashboardView.PANEL);
    }

    /**
     * Takes one whole-home sample.
     *
     * <p>Called on the event dispatch thread by the view's one-second timer.</p>
     *
     * @param appliances every appliance the dashboard knows about; must not be null
     * @throws NullPointerException if {@code appliances} is null
     */
    public void sample(List<ApplianceState> appliances) {
        sample(appliances, Instant.now());
    }

    /**
     * Takes one whole-home sample as of a given instant.
     *
     * <p>The instant is a parameter for the same reason
     * {@link ApplianceState#isStale(Instant)} takes one: whether an appliance counts towards
     * the total is a question about a moment in time, and a method that reads the clock
     * itself can only ever be asked about now. That makes the staleness rule — the one piece
     * of arithmetic here that is easy to get wrong — impossible to test without waiting five
     * real seconds for it.</p>
     *
     * @param appliances every appliance the dashboard knows about; must not be null
     * @param now        the instant to judge staleness against; must not be null
     * @throws NullPointerException if either argument is null
     */
    public void sample(List<ApplianceState> appliances, Instant now) {
        Objects.requireNonNull(appliances, "appliances");
        Objects.requireNonNull(now, "now");

        double total = 0.0;
        int live = 0;
        for (ApplianceState state : appliances) {
            Reading latest = state.getLatest();
            // A stale appliance contributes nothing: its last reading is not evidence about
            // what the house is drawing now.
            if (latest != null && !state.isStale(now)) {
                total += latest.getPowerWatts();
                live++;
            }
        }

        watts[nextIndex] = total;
        reporting[nextIndex] = live;
        nextIndex = (nextIndex + 1) % WINDOW_SECONDS;
        sampleCount = Math.min(sampleCount + 1, WINDOW_SECONDS);
        peakSeen = Math.max(peakSeen, total);
        lastSampleAt = now;

        repaint();
    }

    /** Discards every sample, for a dashboard that has just reconnected to a different server. */
    public void clear() {
        sampleCount = 0;
        nextIndex = 0;
        peakSeen = 0.0;
        lastSampleAt = null;
        repaint();
    }

    /** @return how many samples the strip currently holds, at most {@link #WINDOW_SECONDS}. */
    public int getSampleCount() {
        return sampleCount;
    }

    /** @return the most recent whole-home total in watts, or zero before the first sample. */
    public double getLatestWatts() {
        if (sampleCount == 0) {
            return 0.0;
        }
        return watts[Math.floorMod(nextIndex - 1, WINDOW_SECONDS)];
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int left = MARGIN_LEFT;
            int right = getWidth() - MARGIN_RIGHT;
            int top = MARGIN_TOP;
            int bottom = getHeight() - MARGIN_BOTTOM;

            g.setFont(TITLE_FONT);
            g.setColor(DashboardView.TEXT);
            g.drawString("Whole-home load — last " + WINDOW_SECONDS + " s", left, 16);

            if (sampleCount > 0) {
                String current = String.format(Locale.ROOT, "%,.0f W now · peak %,.0f W",
                        getLatestWatts(), peakSeen);
                g.setFont(AXIS_FONT);
                g.setColor(DashboardView.MUTED);
                g.drawString(current, right - g.getFontMetrics().stringWidth(current), 16);
            }

            if (right - left < 40 || bottom - top < 30) {
                return;
            }
            if (sampleCount == 0) {
                paintPlaceholder(g, left, right, top, bottom);
                return;
            }
            paintStrip(g, left, right, top, bottom);
        } finally {
            g.dispose();
        }
    }

    private void paintPlaceholder(Graphics2D g, int left, int right, int top, int bottom) {
        g.setColor(DashboardView.GRID);
        g.drawRect(left, top, right - left, bottom - top);

        g.setFont(AXIS_FONT);
        g.setColor(DashboardView.MUTED);
        String message = "waiting for the first reading";
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(message, left + (right - left - metrics.stringWidth(message)) / 2,
                (top + bottom) / 2);
    }

    private void paintStrip(Graphics2D g, int left, int right, int top, int bottom) {
        double scale = niceCeiling(Math.max(MINIMUM_SCALE_WATTS, peakSeen));

        g.setFont(AXIS_FONT);
        for (int i = 0; i <= Y_DIVISIONS; i++) {
            int y = bottom - (bottom - top) * i / Y_DIVISIONS;
            g.setColor(DashboardView.GRID);
            g.drawLine(left, y, right, y);

            String watt = String.format(Locale.ROOT, "%,.0f W", scale * i / Y_DIVISIONS);
            g.setColor(DashboardView.MUTED);
            g.drawString(watt, left - 6 - g.getFontMetrics().stringWidth(watt), y + 4);
        }

        // The newest sample sits at the right edge and the strip fills leftwards, so a chart
        // that has been open for ten seconds occupies the right sixth of the axis rather than
        // stretching ten points across the whole of it and pretending to be a minute of data.
        double stepX = (right - left) / (double) (WINDOW_SECONDS - 1);
        Path2D.Double line = new Path2D.Double();
        Path2D.Double fill = new Path2D.Double();

        for (int i = 0; i < sampleCount; i++) {
            int ring = Math.floorMod(nextIndex - sampleCount + i, WINDOW_SECONDS);
            double x = right - (sampleCount - 1 - i) * stepX;
            double y = bottom - (bottom - top) * Math.min(1.0, watts[ring] / scale);
            if (i == 0) {
                line.moveTo(x, y);
                fill.moveTo(x, bottom);
                fill.lineTo(x, y);
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
            if (i == sampleCount - 1) {
                fill.lineTo(x, bottom);
                fill.closePath();
            }
        }

        g.setColor(new Color(DashboardView.ACCENT.getRed(), DashboardView.ACCENT.getGreen(),
                DashboardView.ACCENT.getBlue(), 48));
        g.fill(fill);

        g.setColor(DashboardView.ACCENT);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(line);

        g.setColor(DashboardView.MUTED);
        g.drawString("-" + WINDOW_SECONDS + " s", left, bottom + 14);
        String nowLabel = "now";
        g.drawString(nowLabel, right - g.getFontMetrics().stringWidth(nowLabel), bottom + 14);

        int liveNow = reporting[Math.floorMod(nextIndex - 1, WINDOW_SECONDS)];
        String contributors = liveNow + " appliance(s) reporting";
        g.drawString(contributors, (left + right - g.getFontMetrics().stringWidth(contributors)) / 2,
                bottom + 14);
    }

    /** Rounds an axis maximum up to something a person would have chosen. */
    private static double niceCeiling(double value) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(value)));
        double normalised = value / magnitude;
        double step = normalised <= 1 ? 1 : normalised <= 2 ? 2 : normalised <= 5 ? 5 : 10;
        return step * magnitude;
    }

    @Override
    public String toString() {
        return "LiveChartPanel[samples=" + sampleCount + ", latest=" + getLatestWatts()
                + "W, lastSampleAt=" + lastSampleAt + "]";
    }
}
