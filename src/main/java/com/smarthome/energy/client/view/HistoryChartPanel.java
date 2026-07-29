package com.smarthome.energy.client.view;

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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Swing panel that plots historical usage for a selected appliance over a time window.
 *
 * <p>Pulls its series from {@code HistoryQueryService} (JDBC) and renders a simple
 * time-vs-power line chart. This is the "history" half of the dashboard, complementing the
 * live per-appliance tiles.</p>
 *
 * <p>The chart is drawn with {@code Graphics2D} rather than pulled in from a charting
 * library. A line, four gridlines, and two axis scales are a couple of screens of arithmetic;
 * a dependency would be larger than the thing it replaced and would leave nobody on the team
 * able to explain what happens when the series is empty. Which it often is — an empty
 * result, a device that has never reported, and a database that could not be reached are
 * three different situations, and the panel says which one it is looking at rather than
 * showing blank axes for all three.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming with Swing/AWT (custom painting);
 * Unit III — consumes JDBC query results.</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
public final class HistoryChartPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Dimension MINIMUM_SIZE = new Dimension(420, 220);
    private static final int MARGIN_LEFT = 58;
    private static final int MARGIN_RIGHT = 16;
    private static final int MARGIN_TOP = 26;
    private static final int MARGIN_BOTTOM = 30;
    private static final int Y_DIVISIONS = 4;

    private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private static final Font AXIS_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private transient List<Reading> series = List.of();
    private String title = "History";
    private String emptyMessage = "select an appliance to see its history";

    /** Creates an empty chart. */
    public HistoryChartPanel() {
        setPreferredSize(MINIMUM_SIZE);
        setMinimumSize(MINIMUM_SIZE);
        setOpaque(true);
        setBackground(DashboardView.PANEL);
    }

    /**
     * Replaces the plotted series.
     *
     * @param title    what the chart is showing; must not be null
     * @param readings the series, oldest first; must not be null
     * @throws NullPointerException if either argument is null
     */
    public void setSeries(String title, List<Reading> readings) {
        this.title = Objects.requireNonNull(title, "title");
        this.series = List.copyOf(Objects.requireNonNull(readings, "readings"));
        this.emptyMessage = "no readings stored in this window";
        repaint();
    }

    /**
     * Clears the chart and explains why it is empty.
     *
     * @param message what to show instead of a plot; must not be null
     * @throws NullPointerException if {@code message} is null
     */
    public void setEmpty(String message) {
        this.series = List.of();
        this.emptyMessage = Objects.requireNonNull(message, "message");
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setFont(TITLE_FONT);
            g.setColor(DashboardView.TEXT);
            g.drawString(title, MARGIN_LEFT, 16);

            int left = MARGIN_LEFT;
            int right = getWidth() - MARGIN_RIGHT;
            int top = MARGIN_TOP;
            int bottom = getHeight() - MARGIN_BOTTOM;
            if (right - left < 40 || bottom - top < 40) {
                return;
            }

            if (series.isEmpty()) {
                paintPlaceholder(g, left, right, top, bottom);
                return;
            }
            paintChart(g, left, right, top, bottom);
        } finally {
            g.dispose();
        }
    }

    private void paintPlaceholder(Graphics2D g, int left, int right, int top, int bottom) {
        g.setColor(DashboardView.GRID);
        g.drawRect(left, top, right - left, bottom - top);

        g.setFont(AXIS_FONT);
        g.setColor(DashboardView.MUTED);
        FontMetrics metrics = g.getFontMetrics();
        int x = left + (right - left - metrics.stringWidth(emptyMessage)) / 2;
        g.drawString(emptyMessage, Math.max(left + 4, x), (top + bottom) / 2);
    }

    private void paintChart(Graphics2D g, int left, int right, int top, int bottom) {
        double peak = 1.0;
        for (Reading reading : series) {
            peak = Math.max(peak, reading.getPowerWatts());
        }
        peak = niceCeiling(peak);

        // Horizontal gridlines with their watt labels.
        g.setFont(AXIS_FONT);
        for (int i = 0; i <= Y_DIVISIONS; i++) {
            int y = bottom - (bottom - top) * i / Y_DIVISIONS;
            g.setColor(DashboardView.GRID);
            g.drawLine(left, y, right, y);

            String label = String.format(Locale.ROOT, "%.0f W", peak * i / Y_DIVISIONS);
            g.setColor(DashboardView.MUTED);
            g.drawString(label, left - 6 - g.getFontMetrics().stringWidth(label), y + 4);
        }

        long firstMillis = series.get(0).getReadingEpochMillis();
        long lastMillis = series.get(series.size() - 1).getReadingEpochMillis();
        long span = Math.max(1L, lastMillis - firstMillis);

        Path2D.Double line = new Path2D.Double();
        for (int i = 0; i < series.size(); i++) {
            Reading reading = series.get(i);
            double x = left + (right - left) * (reading.getReadingEpochMillis() - firstMillis) / (double) span;
            double y = bottom - (bottom - top) * Math.min(1.0, reading.getPowerWatts() / peak);
            if (i == 0) {
                line.moveTo(x, y);
            } else {
                line.lineTo(x, y);
            }
        }

        g.setColor(DashboardView.ACCENT);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(line);

        // Time axis: the ends of the window, which is what a reader actually looks for.
        g.setColor(DashboardView.MUTED);
        String start = CLOCK.format(Instant.ofEpochMilli(firstMillis));
        String end = CLOCK.format(Instant.ofEpochMilli(lastMillis));
        g.drawString(start, left, bottom + 16);
        g.drawString(end, right - g.getFontMetrics().stringWidth(end), bottom + 16);

        String count = series.size() + " readings";
        g.drawString(count, (left + right - g.getFontMetrics().stringWidth(count)) / 2, bottom + 16);
    }

    /** Rounds an axis maximum up to something a person would have chosen. */
    private static double niceCeiling(double value) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(value)));
        double normalised = value / magnitude;
        double step = normalised <= 1 ? 1 : normalised <= 2 ? 2 : normalised <= 5 ? 5 : 10;
        return step * magnitude;
    }
}
