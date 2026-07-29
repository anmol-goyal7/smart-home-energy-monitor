package com.smarthome.energy.protocol;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic finite automaton (DFA) that validates the structural well-formedness of
 * a raw sensor message <em>before</em> any parsing is attempted.
 *
 * <p>Every line received from a meter is fed through this DFA one character at a time.
 * The automaton accepts a string only if it matches the exact grammar of the wire
 * format documented in {@code README.md}:</p>
 *
 * <pre>
 *   RDG '|' 'D' int '|' 'T' int '|' 'V' dec '|' 'I' dec '|' 'P' dec '\n'
 * </pre>
 *
 * <p>The DFA has a single start state ({@link State#S0}), a single accepting state
 * ({@link State#S25}), and an explicit dead state ({@link State#DEAD}) for any character
 * that does not match a defined transition. It never back-tracks, so validation is O(n) in
 * the message length with O(1) memory — the classic guarantee of a regular language
 * recogniser. Only strings the DFA accepts are handed to {@link MessageParser}; everything
 * else is rejected at the door, which keeps malformed input out of the numeric parsing path
 * entirely.</p>
 *
 * <h2>The transition table is the specification</h2>
 *
 * <p>The states and edges below are the table printed in the README, entered once here and
 * consulted for two different purposes: {@link #next(State, char)} walks it to decide where
 * a character leads, and {@link #expected(State)} reads the same rows to say what
 * <em>would</em> have been legal. Deriving the diagnostic from the table rather than writing
 * it out separately is what stops the error messages drifting away from the automaton they
 * describe. The edges themselves are built from the constants in {@link MeterMessage}, so
 * the grammar this recognises and the frames the simulator emits cannot disagree.</p>
 *
 * <h2>Deviation from the scaffold</h2>
 *
 * <p>The scaffold sketched {@code State} and {@code next} as private. Both are public here
 * because the states are part of what this class reports, not an implementation detail:
 * {@link ValidationResult} names the state that rejected a line, and stepping the automaton
 * one character at a time is exactly what the Unit V demonstration needs in order to show
 * the machine running.</p>
 *
 * <p>Syllabus mapping: Unit V — Formal languages &amp; automata (a DFA recognising a
 * regular language).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class WireFormatValidator {

    /**
     * The automaton's states, named exactly as in the README's transition table.
     *
     * <p>{@link #S0} is the start state, {@link #S25} the sole accepting state, and
     * {@link #DEAD} the trap: once there, no input can leave.</p>
     */
    public enum State {
        /** Start state; expects the first header character. */
        S0,
        /** Read {@code R}. */
        S1,
        /** Read {@code RD}. */
        S2,
        /** Header {@code RDG} complete. */
        S3,
        /** Read the separator before the device field. */
        S4,
        /** Read the device tag; needs at least one digit. */
        S5,
        /** Inside the device id. */
        S6,
        /** Read the separator before the timestamp field. */
        S7,
        /** Read the timestamp tag; needs at least one digit. */
        S8,
        /** Inside the timestamp. */
        S9,
        /** Read the separator before the voltage field. */
        S10,
        /** Read the voltage tag; needs at least one digit. */
        S11,
        /** Inside the voltage's integer part. */
        S12,
        /** Read the voltage's decimal point; needs at least one fraction digit. */
        S13,
        /** Inside the voltage's fraction. */
        S14,
        /** Read the separator before the current field. */
        S15,
        /** Read the current tag; needs at least one digit. */
        S16,
        /** Inside the current's integer part. */
        S17,
        /** Read the current's decimal point; needs at least one fraction digit. */
        S18,
        /** Inside the current's fraction. */
        S19,
        /** Read the separator before the power field. */
        S20,
        /** Read the power tag; needs at least one digit. */
        S21,
        /** Inside the power's integer part. */
        S22,
        /** Read the power's decimal point; needs at least one fraction digit. */
        S23,
        /** Inside the power's fraction; the terminator from here accepts. */
        S24,
        /** Accepting state: a complete, terminated frame. */
        S25,
        /** Trap state: reached on the first character that does not fit, never left. */
        DEAD;

        /** @return true if this is the accepting state. */
        public boolean isAccepting() {
            return this == S25;
        }

        /** @return true if this is the trap state. */
        public boolean isDead() {
            return this == DEAD;
        }
    }

    /** The state every run begins in. */
    public static final State START = State.S0;

    /**
     * One outgoing edge of the transition table: the input it fires on and where it leads.
     *
     * <p>An edge matches either a single literal character or the whole {@code digit} class;
     * {@link #label()} renders whichever it is for the diagnostic message.</p>
     */
    private record Transition(char literal, boolean digitClass, State target) {

        static Transition on(char literal, State target) {
            return new Transition(literal, false, target);
        }

        static Transition digit(State target) {
            return new Transition('\0', true, target);
        }

        boolean matches(char c) {
            return digitClass ? (c >= '0' && c <= '9') : c == literal;
        }

        String label() {
            return digitClass ? "digit" : describeChar(literal);
        }
    }

    /** The transition table: every state mapped to its outgoing edges, in reading order. */
    private static final Map<State, List<Transition>> TABLE = buildTable();

    /** Creates a validator. The automaton holds no mutable state, so one instance is enough. */
    public WireFormatValidator() {
        // Stateless.
    }

    /**
     * Reports whether a complete frame — terminator included — is in the language.
     *
     * @param frame the raw characters read from the wire; must not be null
     * @return true if the automaton reaches its accepting state having consumed every
     *         character
     * @throws NullPointerException if {@code frame} is null
     */
    public boolean accepts(String frame) {
        return validate(frame).isAccepted();
    }

    /**
     * Runs the automaton over a complete frame and reports the verdict, with the failure
     * position and the legal characters at that position when it rejects.
     *
     * @param frame the raw characters read from the wire, ending in
     *              {@link MeterMessage#TERMINATOR}; must not be null
     * @return the verdict
     * @throws NullPointerException if {@code frame} is null
     */
    public ValidationResult validate(String frame) {
        return run(Objects.requireNonNull(frame, "frame"), false);
    }

    /**
     * As {@link #validate(String)}, for callers whose reader has already stripped the
     * terminator.
     *
     * <p>{@code BufferedReader.readLine()} — and the server's own bounded line reader —
     * hand back the line without its {@code '\n'}, so validating what they return with
     * {@link #validate(String)} would reject every well-formed frame. Rather than have the
     * caller concatenate a terminator onto every line just to satisfy the automaton, this
     * method runs the same table and then applies the terminator transition itself. The
     * language recognised is identical; only the allocation is avoided.</p>
     *
     * @param line one line as read from the stream, without its terminator; must not be null
     * @return the verdict; a rejection caused by the implied terminator is reported as the
     *         line having ended early rather than as a rejected {@code '\n'}, because the
     *         caller never saw that character
     * @throws NullPointerException if {@code line} is null
     */
    public ValidationResult validateLine(String line) {
        return run(Objects.requireNonNull(line, "line"), true);
    }

    /**
     * The transition function: where {@code current} goes on reading {@code c}.
     *
     * @param current the state the automaton is in; must not be null
     * @param c       the next input character
     * @return the next state, or {@link State#DEAD} if no edge matches
     * @throws NullPointerException if {@code current} is null
     */
    public static State next(State current, char c) {
        for (Transition t : TABLE.get(Objects.requireNonNull(current, "current"))) {
            if (t.matches(c)) {
                return t.target();
            }
        }
        return State.DEAD;
    }

    /**
     * Describes, in the words the error message uses, what may legally follow in a state.
     *
     * @param state the state to describe; must not be null
     * @return e.g. {@code "digit or '|'"}, or {@code "end of input"} for a state with no
     *         outgoing edges
     * @throws NullPointerException if {@code state} is null
     */
    public static String expected(State state) {
        List<Transition> edges = TABLE.get(Objects.requireNonNull(state, "state"));
        if (edges.isEmpty()) {
            return "end of input";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < edges.size(); i++) {
            if (i > 0) {
                out.append(i == edges.size() - 1 ? " or " : ", ");
            }
            out.append(edges.get(i).label());
        }
        return out.toString();
    }

    /** Drives the automaton over {@code input}, optionally applying the terminator at the end. */
    private static ValidationResult run(String input, boolean impliedTerminator) {
        State state = START;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            State target = next(state, c);
            if (target.isDead()) {
                return ValidationResult.rejected(i, state, c);
            }
            state = target;
        }
        if (impliedTerminator) {
            State target = next(state, MeterMessage.TERMINATOR);
            if (target.isDead()) {
                return ValidationResult.truncated(input.length(), state);
            }
            state = target;
        }
        return state.isAccepting() ? ValidationResult.accepted()
                : ValidationResult.truncated(input.length(), state);
    }

    /**
     * Builds the transition table from the README's rows, entered once.
     *
     * <p>Edge order within a state matters only for the wording of {@link #expected(State)}
     * — the classes never overlap, so the automaton stays deterministic whatever the
     * order.</p>
     */
    private static Map<State, List<Transition>> buildTable() {
        Map<State, List<Transition>> table = new EnumMap<>(State.class);
        for (State state : State.values()) {
            table.put(state, List.of());
        }

        // Header: R D G, then the first separator.
        define(table, State.S0, Transition.on(MeterMessage.HEADER.charAt(0), State.S1));
        define(table, State.S1, Transition.on(MeterMessage.HEADER.charAt(1), State.S2));
        define(table, State.S2, Transition.on(MeterMessage.HEADER.charAt(2), State.S3));
        define(table, State.S3, Transition.on(MeterMessage.DELIMITER, State.S4));

        // D<digits>|
        define(table, State.S4, Transition.on(MeterMessage.TAG_DEVICE, State.S5));
        define(table, State.S5, Transition.digit(State.S6));
        define(table, State.S6, Transition.digit(State.S6),
                Transition.on(MeterMessage.DELIMITER, State.S7));

        // T<digits>|
        define(table, State.S7, Transition.on(MeterMessage.TAG_TIMESTAMP, State.S8));
        define(table, State.S8, Transition.digit(State.S9));
        define(table, State.S9, Transition.digit(State.S9),
                Transition.on(MeterMessage.DELIMITER, State.S10));

        // V<digits>.<digits>|
        define(table, State.S10, Transition.on(MeterMessage.TAG_VOLTAGE, State.S11));
        define(table, State.S11, Transition.digit(State.S12));
        define(table, State.S12, Transition.digit(State.S12), Transition.on('.', State.S13));
        define(table, State.S13, Transition.digit(State.S14));
        define(table, State.S14, Transition.digit(State.S14),
                Transition.on(MeterMessage.DELIMITER, State.S15));

        // I<digits>.<digits>|
        define(table, State.S15, Transition.on(MeterMessage.TAG_CURRENT, State.S16));
        define(table, State.S16, Transition.digit(State.S17));
        define(table, State.S17, Transition.digit(State.S17), Transition.on('.', State.S18));
        define(table, State.S18, Transition.digit(State.S19));
        define(table, State.S19, Transition.digit(State.S19),
                Transition.on(MeterMessage.DELIMITER, State.S20));

        // P<digits>.<digits> then the terminator, which accepts.
        define(table, State.S20, Transition.on(MeterMessage.TAG_POWER, State.S21));
        define(table, State.S21, Transition.digit(State.S22));
        define(table, State.S22, Transition.digit(State.S22), Transition.on('.', State.S23));
        define(table, State.S23, Transition.digit(State.S24));
        define(table, State.S24, Transition.digit(State.S24),
                Transition.on(MeterMessage.TERMINATOR, State.S25));

        // S25 and DEAD keep the empty edge list installed above: nothing may follow a
        // complete frame, and nothing leaves the trap.
        return Collections.unmodifiableMap(table);
    }

    private static void define(Map<State, List<Transition>> table, State from, Transition... edges) {
        table.put(from, List.of(edges));
    }

    /** Renders one character the way the diagnostic message quotes it. */
    private static String describeChar(char c) {
        return switch (c) {
            case '\n' -> "'\\n'";
            case '\r' -> "'\\r'";
            case '\t' -> "'\\t'";
            default -> c < 0x20 || c == 0x7F
                    ? String.format("0x%02X", (int) c)
                    : "'" + c + "'";
        };
    }

    /**
     * The verdict of one run of the automaton, carrying enough to point at the mistake.
     *
     * <p>A plain accept/reject is enough to protect the parser but useless for diagnosing a
     * misbehaving meter. The automaton already knows the index at which it entered the trap
     * state and which transitions were defined for the state it left, so the position and
     * the set of legal characters cost one integer of extra state and no second pass — the
     * diagnostic falls out of the automaton's structure rather than being bolted on.</p>
     */
    public static final class ValidationResult {

        private static final ValidationResult ACCEPTED = new ValidationResult(true, -1, State.S25, '\0', false);

        private final boolean accepted;
        private final int errorIndex;
        private final State state;
        private final char rejectedChar;
        private final boolean truncated;

        private ValidationResult(boolean accepted, int errorIndex, State state,
                                 char rejectedChar, boolean truncated) {
            this.accepted = accepted;
            this.errorIndex = errorIndex;
            this.state = state;
            this.rejectedChar = rejectedChar;
            this.truncated = truncated;
        }

        static ValidationResult accepted() {
            return ACCEPTED;
        }

        static ValidationResult rejected(int index, State state, char rejectedChar) {
            return new ValidationResult(false, index, state, rejectedChar, false);
        }

        static ValidationResult truncated(int index, State state) {
            return new ValidationResult(false, index, state, '\0', true);
        }

        /** @return true if the frame is in the language. */
        public boolean isAccepted() {
            return accepted;
        }

        /**
         * @return the zero-based index of the offending character, or of the position just
         *         past the end when the input stopped early; {@code -1} when accepted
         */
        public int getErrorIndex() {
            return errorIndex;
        }

        /**
         * @return the state the automaton was in when it rejected — the state whose
         *         expectations were not met, not the trap it fell into
         */
        public State getState() {
            return state;
        }

        /** @return true if the input ran out mid-frame rather than carrying a bad character. */
        public boolean isTruncated() {
            return truncated;
        }

        /**
         * @return the character that had no transition; meaningless when
         *         {@link #isTruncated()} is true
         */
        public char getRejectedChar() {
            return rejectedChar;
        }

        /** @return what would have been legal at {@link #getErrorIndex()}, in words. */
        public String getExpected() {
            return expected(state);
        }

        /**
         * @return a one-line explanation, e.g.
         *         {@code col 28: in state S14, expected digit or '|', got 'x'}; empty when
         *         the frame was accepted
         */
        public String getMessage() {
            if (accepted) {
                return "";
            }
            return "col " + errorIndex + ": in state " + state + ", expected " + getExpected()
                    + ", got " + (truncated ? "end of input" : describeChar(rejectedChar));
        }

        /**
         * Renders the frame, a caret under the offending column, and the explanation — the
         * form the server logs a rejection in.
         *
         * <p>Control characters in the frame are shown as {@code ·} so that one input
         * character stays one printed column and the caret lands where it should.</p>
         *
         * @param frame the frame this result came from; must not be null
         * @return a three-line diagram, or a single line of confirmation when accepted
         * @throws NullPointerException if {@code frame} is null
         */
        public String describe(String frame) {
            Objects.requireNonNull(frame, "frame");
            if (accepted) {
                return "accepted: " + printable(frame);
            }
            return printable(frame) + System.lineSeparator()
                    + " ".repeat(Math.max(0, errorIndex)) + "^" + System.lineSeparator()
                    + "  " + getMessage();
        }

        /** Strips the terminator and replaces the remaining control characters, column for column. */
        private static String printable(String frame) {
            StringBuilder out = new StringBuilder(frame.length());
            for (int i = 0; i < frame.length(); i++) {
                char c = frame.charAt(i);
                if (c == MeterMessage.TERMINATOR && i == frame.length() - 1) {
                    break;
                }
                out.append(c < 0x20 || c == 0x7F ? '·' : c);
            }
            return out.toString();
        }

        @Override
        public String toString() {
            return accepted ? "ValidationResult[accepted]" : "ValidationResult[" + getMessage() + "]";
        }
    }
}
