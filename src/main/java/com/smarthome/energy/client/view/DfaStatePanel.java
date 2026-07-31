package com.smarthome.energy.client.view;

import com.smarthome.energy.protocol.MeterMessage;
import com.smarthome.energy.protocol.WireFormatValidator;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Objects;

/**
 * The validating automaton, running, with its transition table beside it.
 *
 * <p>Every frame the system accepts has been through {@link WireFormatValidator}, and the
 * claim that it is a DFA — one pass, no backtracking, constant memory, an explicit trap state
 * — is the Unit V content of this project. A test suite proves the claim; this panel
 * <em>shows</em> it. Characters are fed in one at a time, the current state is highlighted in
 * the table, and a character with no outgoing edge drops the machine into {@code DEAD}, which
 * flashes and stays there however much input follows.</p>
 *
 * <h2>It runs the real automaton, not a drawing of one</h2>
 *
 * <p>The rows are built by asking {@link WireFormatValidator#expected(WireFormatValidator.State)}
 * what each state permits, and each step is {@link WireFormatValidator#next} — the same static
 * method the server calls on every meter frame. There is no second copy of the transition
 * table here to drift out of step with the first. That is also why those two methods are
 * public on the validator: the automaton's structure is something this system reports, not an
 * implementation detail.</p>
 *
 * <h2>Why the frame is editable</h2>
 *
 * <p>The interesting half of a recogniser is what it rejects, and the live feed carries only
 * frames that were already accepted — the server dropped the malformed ones before the
 * dashboard could ever see them. Loading the latest live frame and then letting the operator
 * corrupt one character of it by hand is the whole demonstration: the same string, one
 * character different, and the machine walks into the trap at exactly the column that
 * changed.</p>
 *
 * <p>Syllabus mapping: Unit V — Formal languages &amp; automata (a DFA, visibly running);
 * Unit II — GUI programming with Swing/AWT (custom painting, timers).</p>
 *
 * @author Bhumika Rajput (BhumikaRajput28)
 */
public final class DfaStatePanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Milliseconds between characters while the automaton is running. */
    private static final int STEP_INTERVAL_MS = 120;

    /** Milliseconds between flashes of the trap state after a rejection. */
    private static final int FLASH_INTERVAL_MS = 220;

    /** How many times the trap state flashes before settling. */
    private static final int FLASH_COUNT = 6;

    /** Shown in place of the terminator, so the final transition has something to point at. */
    private static final char TERMINATOR_GLYPH = '⏎';

    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    private static final Font MONO_BOLD = new Font(Font.MONOSPACED, Font.BOLD, 13);
    private static final Font LABEL = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

    private final JTextField frameField = new JTextField();
    private final JLabel verdictLabel = new JLabel(" ");
    private final TapePanel tape = new TapePanel();
    private final TransitionTablePanel transitions = new TransitionTablePanel();
    private final JButton runButton = new JButton("Run");
    private final JButton stepButton = new JButton("Step");
    private final JButton useLiveButton = new JButton("Use latest live frame");

    private final transient Timer stepTimer;
    private final transient Timer flashTimer;

    /** The string being fed in, terminator included. */
    private String input = "";

    /** How many characters of {@link #input} the automaton has consumed. */
    private int position;

    private WireFormatValidator.State state = WireFormatValidator.START;
    private boolean rejected;
    private boolean flashOn;
    private int flashesLeft;

    /** The most recent frame seen on the live feed, for the "use latest" button. */
    private String liveFrame;

    /** Creates the panel with a well-formed example frame loaded. */
    public DfaStatePanel() {
        stepTimer = new Timer(STEP_INTERVAL_MS, e -> step());
        flashTimer = new Timer(FLASH_INTERVAL_MS, e -> flash());
        build();
        load("RDG|D3|T1721817600000|V228.40|I4.10|P998.20");
    }

    /**
     * Records the newest frame from the live feed, enabling the "use latest" button.
     *
     * <p>Called on the event dispatch thread. Only the most recent frame is kept: this is a
     * demonstration of the automaton, not a capture buffer.</p>
     *
     * @param frame the frame as it appeared on the wire, with or without its terminator; must
     *              not be null
     * @throws NullPointerException if {@code frame} is null
     */
    public void setLiveFrame(String frame) {
        Objects.requireNonNull(frame, "frame");
        this.liveFrame = stripTerminator(frame);
        useLiveButton.setEnabled(true);
    }

    /**
     * Loads a frame and rewinds the automaton to its start state.
     *
     * @param frame the frame to run, without its terminator; must not be null
     * @throws NullPointerException if {@code frame} is null
     */
    public void load(String frame) {
        Objects.requireNonNull(frame, "frame");
        frameField.setText(frame);
        reset();
    }

    // ---------------------------------------------------------------- construction

    private void build() {
        setLayout(new BorderLayout(0, 6));
        setBackground(DashboardView.PANEL);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildControls(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(verdictLabel, BorderLayout.SOUTH);

        verdictLabel.setFont(LABEL);
        verdictLabel.setForeground(DashboardView.MUTED);
    }

    private JComponent buildControls() {
        JPanel controls = new JPanel(new BorderLayout(6, 4));
        controls.setBackground(DashboardView.PANEL);

        JLabel caption = new JLabel("Frame");
        caption.setForeground(DashboardView.MUTED);
        caption.setFont(LABEL);

        frameField.setFont(MONO);
        frameField.setBackground(DashboardView.BACKGROUND);
        frameField.setForeground(DashboardView.TEXT);
        frameField.setCaretColor(DashboardView.TEXT);
        frameField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DashboardView.GRID),
                BorderFactory.createEmptyBorder(3, 5, 3, 5)));
        // Any edit invalidates a run in progress: the machine would otherwise carry on
        // stepping through a string that is no longer the one on screen.
        frameField.addActionListener(e -> reset());

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.LINE_AXIS));
        buttons.setBackground(DashboardView.PANEL);

        runButton.addActionListener(e -> toggleRun());
        stepButton.addActionListener(e -> {
            stopRunning();
            step();
        });
        JButton resetButton = new JButton("Reset");
        resetButton.addActionListener(e -> reset());

        useLiveButton.setEnabled(false);
        useLiveButton.addActionListener(e -> {
            if (liveFrame != null) {
                load(liveFrame);
            }
        });

        JButton corruptButton = new JButton("Corrupt one character");
        corruptButton.setToolTipText("Overwrites one character so the automaton has something "
                + "to reject — the demonstration the live feed cannot supply");
        corruptButton.addActionListener(e -> corrupt());

        buttons.add(stepButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(runButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(resetButton);
        buttons.add(Box.createHorizontalStrut(16));
        buttons.add(useLiveButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(corruptButton);
        buttons.add(Box.createHorizontalGlue());

        JPanel field = new JPanel(new BorderLayout(6, 0));
        field.setBackground(DashboardView.PANEL);
        field.add(caption, BorderLayout.WEST);
        field.add(frameField, BorderLayout.CENTER);

        controls.add(field, BorderLayout.NORTH);
        controls.add(buttons, BorderLayout.SOUTH);
        return controls;
    }

    private JComponent buildBody() {
        JScrollPane scroll = new JScrollPane(transitions);
        scroll.setBorder(BorderFactory.createLineBorder(DashboardView.GRID));
        scroll.getViewport().setBackground(DashboardView.PANEL);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel body = new JPanel(new BorderLayout(0, 6));
        body.setBackground(DashboardView.PANEL);
        body.add(tape, BorderLayout.NORTH);
        body.add(scroll, BorderLayout.CENTER);
        return body;
    }

    // ---------------------------------------------------------------- the automaton

    /** Rewinds to the start state with whatever is currently in the frame field. */
    private void reset() {
        stopRunning();
        flashTimer.stop();
        flashesLeft = 0;
        flashOn = false;

        // The terminator is part of the language, so it is part of the string being run. It is
        // appended here rather than typed by the operator, because a text field cannot hold a
        // newline and a grammar that ends in one has to be shown ending in one.
        input = stripTerminator(frameField.getText()) + MeterMessage.TERMINATOR;
        position = 0;
        state = WireFormatValidator.START;
        rejected = false;

        stepButton.setEnabled(true);
        runButton.setEnabled(true);
        runButton.setText("Run");
        setVerdict("ready — " + input.length() + " character(s) to read, starting in "
                + WireFormatValidator.START, DashboardView.MUTED);
        repaintAll();
    }

    /** Feeds one character in. */
    private void step() {
        if (rejected || position >= input.length()) {
            stopRunning();
            return;
        }

        char c = input.charAt(position);
        WireFormatValidator.State target = WireFormatValidator.next(state, c);
        position++;

        if (target.isDead()) {
            rejected = true;
            state = target;
            stopRunning();
            stepButton.setEnabled(false);
            setVerdict("REJECTED at column " + (position - 1) + ": expected "
                            + WireFormatValidator.expected(previousState()) + ", got " + describe(c),
                    DashboardView.CRITICAL);
            startFlashing();
            repaintAll();
            return;
        }

        state = target;
        if (position == input.length()) {
            stopRunning();
            stepButton.setEnabled(false);
            if (state.isAccepting()) {
                setVerdict("ACCEPTED — " + input.length() + " characters, one pass, ended in "
                        + state, DashboardView.OK);
            } else {
                setVerdict("REJECTED — input ended in " + state + ", which is not accepting; "
                        + "expected " + WireFormatValidator.expected(state), DashboardView.CRITICAL);
                rejected = true;
                startFlashing();
            }
        } else {
            setVerdict("in " + state + " after " + position + " character(s); next may be "
                    + WireFormatValidator.expected(state), DashboardView.MUTED);
        }
        repaintAll();
    }

    /**
     * The state the machine was in before the rejecting character.
     *
     * <p>Recomputed by re-running the prefix rather than remembered, because the whole claim
     * being demonstrated is that the automaton is a pure function of the input read so far —
     * so the panel should be able to recover any position from the input alone.</p>
     */
    private WireFormatValidator.State previousState() {
        WireFormatValidator.State walked = WireFormatValidator.START;
        for (int i = 0; i < position - 1; i++) {
            walked = WireFormatValidator.next(walked, input.charAt(i));
        }
        return walked;
    }

    private void toggleRun() {
        if (stepTimer.isRunning()) {
            stopRunning();
            return;
        }
        if (rejected || position >= input.length()) {
            reset();
        }
        stepTimer.start();
        runButton.setText("Pause");
    }

    private void stopRunning() {
        stepTimer.stop();
        runButton.setText("Run");
    }

    /** Overwrites one character of the frame so there is something to reject. */
    private void corrupt() {
        String current = stripTerminator(frameField.getText());
        if (current.isEmpty()) {
            return;
        }
        // Deliberately mid-frame rather than at a random position: a corruption in the header
        // rejects at column 0 and demonstrates nothing about the automaton's progress through
        // the fields.
        int index = current.length() / 2;
        char replacement = current.charAt(index) == 'x' ? 'Q' : 'x';
        frameField.setText(current.substring(0, index) + replacement + current.substring(index + 1));
        reset();
    }

    private void startFlashing() {
        flashesLeft = FLASH_COUNT;
        flashOn = true;
        flashTimer.start();
    }

    private void flash() {
        flashOn = !flashOn;
        if (--flashesLeft <= 0) {
            flashTimer.stop();
            flashOn = true;
        }
        transitions.repaint();
    }

    private void setVerdict(String message, Color colour) {
        verdictLabel.setText(message);
        verdictLabel.setForeground(colour);
    }

    private void repaintAll() {
        tape.repaint();
        transitions.scrollToActiveRow();
        transitions.repaint();
    }

    private static String stripTerminator(String frame) {
        String trimmed = frame.endsWith(String.valueOf(MeterMessage.TERMINATOR))
                ? frame.substring(0, frame.length() - 1)
                : frame;
        return trimmed.endsWith("\r") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** Renders one character the way the verdict line quotes it. */
    private static String describe(char c) {
        return switch (c) {
            case '\n' -> "'\\n'";
            case '\r' -> "'\\r'";
            case '\t' -> "'\\t'";
            default -> c < 0x20 || c == 0x7F ? String.format("0x%02X", (int) c) : "'" + c + "'";
        };
    }

    // ---------------------------------------------------------------- painted children

    /** The input string with the consumed prefix marked and a caret under the next character. */
    private final class TapePanel extends JPanel {

        private static final long serialVersionUID = 1L;

        private static final int PADDING = 8;
        private static final int CARET_HEIGHT = 14;

        TapePanel() {
            setBackground(DashboardView.BACKGROUND);
            setBorder(BorderFactory.createLineBorder(DashboardView.GRID));
            setPreferredSize(new Dimension(400, 52));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(MONO);
                int charWidth = g.getFontMetrics().charWidth('0');
                int baseline = 24;

                for (int i = 0; i < input.length(); i++) {
                    char c = input.charAt(i);
                    int x = PADDING + i * charWidth;
                    if (x > getWidth() - PADDING) {
                        break;
                    }

                    if (i < position) {
                        g.setColor(rejected && i == position - 1
                                ? DashboardView.CRITICAL : DashboardView.OK);
                    } else if (i == position) {
                        g.setColor(DashboardView.ACCENT);
                    } else {
                        g.setColor(DashboardView.MUTED);
                    }
                    g.setFont(i == position ? MONO_BOLD : MONO);
                    g.drawString(String.valueOf(c == MeterMessage.TERMINATOR ? TERMINATOR_GLYPH : c),
                            x, baseline);
                }

                // The caret sits under the character about to be read, or under the last one
                // read when the run is over, which is where the eye is already looking.
                int caretIndex = Math.min(position, input.length() - 1);
                int caretX = PADDING + caretIndex * charWidth;
                if (caretX <= getWidth() - PADDING) {
                    g.setColor(rejected ? DashboardView.CRITICAL : DashboardView.ACCENT);
                    g.drawLine(caretX, baseline + 4, caretX + charWidth - 1, baseline + 4);
                    g.drawLine(caretX + charWidth / 2, baseline + 4,
                            caretX + charWidth / 2, baseline + CARET_HEIGHT);
                }
            } finally {
                g.dispose();
            }
        }
    }

    /** The transition table: one row per state, the current one highlighted. */
    private final class TransitionTablePanel extends JPanel {

        private static final long serialVersionUID = 1L;

        private static final int ROW_HEIGHT = 20;
        private static final int STATE_COLUMN_WIDTH = 70;
        private static final int PADDING = 8;

        TransitionTablePanel() {
            setBackground(DashboardView.PANEL);
            setPreferredSize(new Dimension(400,
                    WireFormatValidator.State.values().length * ROW_HEIGHT + PADDING * 2));
        }

        /** Keeps the highlighted row on screen as the machine walks down the table. */
        void scrollToActiveRow() {
            int index = state.ordinal();
            scrollRectToVisible(new Rectangle(0, PADDING + index * ROW_HEIGHT - ROW_HEIGHT,
                    getWidth(), ROW_HEIGHT * 3));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                WireFormatValidator.State[] states = WireFormatValidator.State.values();
                for (int i = 0; i < states.length; i++) {
                    paintRow(g, states[i], PADDING + i * ROW_HEIGHT);
                }
            } finally {
                g.dispose();
            }
        }

        private void paintRow(Graphics2D g, WireFormatValidator.State row, int y) {
            boolean active = row == state;
            boolean trapFlashing = row.isDead() && rejected && flashOn;

            if (trapFlashing) {
                g.setColor(new Color(DashboardView.CRITICAL.getRed(),
                        DashboardView.CRITICAL.getGreen(), DashboardView.CRITICAL.getBlue(), 90));
                g.fillRect(0, y, getWidth(), ROW_HEIGHT);
            } else if (active) {
                g.setColor(DashboardView.GRID);
                g.fillRect(0, y, getWidth(), ROW_HEIGHT);
            }

            Color foreground;
            if (row.isDead()) {
                foreground = rejected ? DashboardView.CRITICAL : DashboardView.STALE;
            } else if (row.isAccepting()) {
                foreground = active && !rejected ? DashboardView.OK : DashboardView.MUTED;
            } else if (active) {
                foreground = DashboardView.TEXT;
            } else {
                foreground = DashboardView.MUTED;
            }

            g.setFont(active ? MONO_BOLD : MONO);
            g.setColor(foreground);
            g.drawString((active ? "▶ " : "  ") + row.name(), PADDING, y + 14);

            g.setFont(MONO);
            String expects = row.isAccepting()
                    ? "accepting — a complete frame"
                    : row.isDead()
                        ? "trap — nothing leaves this state"
                        : "on " + WireFormatValidator.expected(row);
            g.drawString(expects, PADDING + STATE_COLUMN_WIDTH, y + 14);
        }
    }
}
