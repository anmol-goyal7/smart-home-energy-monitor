package com.smarthome.energy.client.view;

import com.smarthome.energy.client.model.ApplianceState;
import com.smarthome.energy.model.Reading;
import com.smarthome.energy.model.Severity;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Swing panel showing the live state of one appliance.
 *
 * <p>Renders the appliance's name, its latest voltage/current/power, and a small sparkline
 * of recent power draw. Its colour reflects the appliance's alert status so an operator can
 * spot a spike, sag, or overload at a glance. Bound to one {@link ApplianceState}.</p>
 *
 * <p>The whole tile is drawn in {@link #paintComponent(Graphics)} rather than assembled from
 * labels. A tile is a single picture whose parts line up with each other — the sparkline
 * shares the accent colour of the status dot, the readouts are placed on a baseline grid —
 * and expressing that as nested layout managers costs more code than the drawing does, for
 * less control. It is also the Unit II teaching point: this is what a component looks like
 * when you own its pixels.</p>
 *
 * <p>The panel reads its state and repaints; it never writes to the model.</p>
 *
 * <p>Syllabus mapping: Unit II — GUI programming with Swing/AWT (custom {@code JPanel},
 * painting).</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
public final class AppliancePanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Tile size; the grid in {@link DashboardView} lays these out in rows. */
    private static final Dimension TILE_SIZE = new Dimension(250, 152);

    private static final int PADDING = 12;
    private static final int SPARKLINE_HEIGHT = 34;

    private static final Font NAME_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final Font VALUE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 17);
    private static final Font UNIT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    private static final Font DETAIL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);

    private final transient ApplianceState state;

    /**
     * @param state the appliance this tile shows; must not be null
     * @throws NullPointerException if {@code state} is null
     */
    public AppliancePanel(ApplianceState state) {
        this.state = Objects.requireNonNull(state, "state");
        setPreferredSize(TILE_SIZE);
        setMinimumSize(TILE_SIZE);
        setOpaque(false);
        setToolTipText("Device " + state.getDeviceId() + " — " + state.getName());
    }

    /** @return the appliance this tile is bound to. */
    public ApplianceState getState() {
        return state;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            Color accent = accentColour();

            g.setColor(DashboardView.PANEL);
            g.fillRoundRect(0, 0, width - 1, height - 1, 10, 10);
            g.setColor(DashboardView.GRID);
            g.drawRoundRect(0, 0, width - 1, height - 1, 10, 10);

            // A coloured spine down the left edge: readable at a glance across a wall of tiles.
            g.setColor(accent);
            g.fillRoundRect(0, 0, 4, height - 1, 6, 6);

            paintHeader(g, width, accent);
            paintReadouts(g, width);
            paintSparkline(g, width, height, accent);
        } finally {
            g.dispose();
        }
    }

    /** Name, device id, and the freshness dot. */
    private void paintHeader(Graphics2D g, int width, Color accent) {
        g.setFont(NAME_FONT);
        g.setColor(DashboardView.TEXT);
        g.drawString(truncate(g, state.getName(), width - PADDING * 2 - 26), PADDING, PADDING + 10);

        g.setFont(DETAIL_FONT);
        g.setColor(DashboardView.MUTED);
        g.drawString("device " + state.getDeviceId() + " · " + freshness(), PADDING, PADDING + 24);

        g.setColor(accent);
        g.fillOval(width - PADDING - 9, PADDING + 1, 9, 9);
    }

    /** The three measured values, side by side. */
    private void paintReadouts(Graphics2D g, int width) {
        Reading latest = state.getLatest();
        int columnWidth = (width - PADDING * 2) / 3;
        int baseline = PADDING + 54;

        // Voltage and current keep their decimals: the whole point of the system is that 253.4 V
        // and 253.0 V are on opposite sides of a threshold. Watts round off, because nobody
        // reads a fridge's draw to a tenth of a watt and the extra digits crowd the tile.
        drawReadout(g, PADDING, baseline, columnWidth,
                latest == null ? "—" : String.format(Locale.ROOT, "%.1f", latest.getVoltage()), "V");
        drawReadout(g, PADDING + columnWidth, baseline, columnWidth,
                latest == null ? "—" : String.format(Locale.ROOT, "%.2f", latest.getCurrent()), "A");
        drawReadout(g, PADDING + columnWidth * 2, baseline, columnWidth,
                latest == null ? "—" : format(latest.getPowerWatts()), "W");
    }

    private void drawReadout(Graphics2D g, int x, int baseline, int columnWidth, String value, String unit) {
        g.setFont(VALUE_FONT);
        g.setColor(DashboardView.TEXT);
        g.drawString(value, x, baseline);

        g.setFont(UNIT_FONT);
        g.setColor(DashboardView.MUTED);
        g.drawString(unit, x, baseline + 13);
    }

    /** The rolling power trace along the bottom of the tile. */
    private void paintSparkline(Graphics2D g, int width, int height, Color accent) {
        int top = height - PADDING - SPARKLINE_HEIGHT;
        int left = PADDING;
        int right = width - PADDING;
        int bottom = height - PADDING;

        g.setColor(DashboardView.GRID);
        g.drawLine(left, bottom, right, bottom);

        double[] samples = state.getRecentPower();
        if (samples.length < 2) {
            g.setFont(DETAIL_FONT);
            g.setColor(DashboardView.MUTED);
            g.drawString("waiting for readings…", left, bottom - 10);
            return;
        }

        // Scale to the window's own peak, with a floor so an idle appliance does not have its
        // noise magnified into a mountain range.
        double peak = 1.0;
        for (double sample : samples) {
            peak = Math.max(peak, sample);
        }

        double stepX = (right - left) / (double) (samples.length - 1);
        Path2D.Double trace = new Path2D.Double();
        for (int i = 0; i < samples.length; i++) {
            double x = left + i * stepX;
            double y = bottom - (samples[i] / peak) * (bottom - top);
            if (i == 0) {
                trace.moveTo(x, y);
            } else {
                trace.lineTo(x, y);
            }
        }

        Path2D.Double filled = (Path2D.Double) trace.clone();
        filled.lineTo(right, bottom);
        filled.lineTo(left, bottom);
        filled.closePath();
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 40));
        g.fill(filled);

        g.setColor(accent);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(trace);

        g.setFont(DETAIL_FONT);
        g.setColor(DashboardView.MUTED);
        g.drawString("peak " + format(peak) + " W", left, top - 2);
    }

    /**
     * The tile's accent colour: alert severity if the rule engine has flagged this appliance,
     * grey if the feed has gone quiet, green otherwise.
     */
    private Color accentColour() {
        Severity severity = state.getAlertSeverity();
        if (severity != null) {
            return switch (severity) {
                case CRITICAL -> DashboardView.CRITICAL;
                case WARNING -> DashboardView.WARNING;
                case INFO -> DashboardView.ACCENT;
            };
        }
        return state.isStale(Instant.now()) ? DashboardView.STALE : DashboardView.OK;
    }

    /** "live", or how long the appliance has been silent. */
    private String freshness() {
        Instant last = state.getLastUpdatedAt();
        if (last == null) {
            return "no data";
        }
        long seconds = Duration.between(last, Instant.now()).toSeconds();
        return seconds <= ApplianceState.STALE_AFTER.toSeconds() ? "live" : seconds + "s ago";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, value >= 100 ? "%.0f" : "%.1f", value);
    }

    /** Shortens a label with an ellipsis so a long appliance name cannot overrun its tile. */
    private static String truncate(Graphics2D g, String text, int maxWidth) {
        if (g.getFontMetrics().stringWidth(text) <= maxWidth) {
            return text;
        }
        String shortened = text;
        while (shortened.length() > 1
                && g.getFontMetrics().stringWidth(shortened + "…") > maxWidth) {
            shortened = shortened.substring(0, shortened.length() - 1);
        }
        return shortened + "…";
    }
}
