package com.smarthome.energy.protocol;

import com.smarthome.energy.model.Reading;

import java.util.Objects;

/**
 * Converts a wire line that has already been accepted by {@link WireFormatValidator}
 * into a typed {@link Reading}.
 *
 * <p>Separation of concerns is deliberate: the DFA decides <em>whether</em> a line is
 * syntactically legal, and this parser is responsible only for <em>extracting</em> the
 * five typed fields (device id, timestamp, voltage, current, power) from a line already
 * known to be well-formed. Because validation happened first, the parser can assume the
 * structure and focus on {@code parseInt}/{@code parseDouble} of each field.</p>
 *
 * <h2>What is still left to check</h2>
 *
 * <p>Syntactic legality is not the same as usability, and three things survive the
 * automaton:</p>
 *
 * <ul>
 *   <li><strong>Digits that do not fit their type.</strong> {@code D<40 digits>} is a
 *       perfectly legal frame and an impossible {@code int}.</li>
 *   <li><strong>Device id zero.</strong> The grammar's {@code digit+} accepts {@code D0},
 *       but no device is ever keyed 0 — {@code devices.device_id} is auto-increment — and
 *       {@link Reading} rejects it outright.</li>
 *   <li><strong>Values wider than their column.</strong> {@code voltage} is a
 *       {@code DECIMAL(6,2)}: a legal-looking {@code V123456.78} would be refused by MySQL
 *       on the ingest path, where the exception surfaces on a dispatcher worker with the
 *       original line long since discarded.</li>
 * </ul>
 *
 * <p>All three are turned into a {@link ProtocolException} naming the field and the value,
 * which the handler logs and skips — the same treatment a malformed line gets, decided at
 * the point where the offending text is still in hand.</p>
 *
 * <p>Syllabus mapping: Unit V — Formal languages &amp; automata (tokenising the accepted
 * string); Unit I — Java OOP fundamentals.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class MessageParser {

    /** Widest value {@code DECIMAL(6,2)} can store: {@code voltage} and {@code current_amp}. */
    private static final double MAX_DECIMAL_6_2 = 9_999.99;

    /** Widest value {@code DECIMAL(10,2)} can store: {@code power_watts}. */
    private static final double MAX_DECIMAL_10_2 = 99_999_999.99;

    /** Creates a parser. It holds no state, so one instance serves every connection. */
    public MessageParser() {
        // Stateless.
    }

    /**
     * Extracts the five fields of an accepted line.
     *
     * @param validatedLine a line the validator has accepted, with or without its
     *                      terminator; must not be null
     * @return the reading it carries
     * @throws ProtocolException    if a field does not fit its type, its column, or the
     *                              domain rule that device ids are positive — or if the line
     *                              is structurally wrong, which means it reached here without
     *                              being validated
     * @throws NullPointerException if {@code validatedLine} is null
     */
    public Reading parse(String validatedLine) throws ProtocolException {
        Objects.requireNonNull(validatedLine, "validatedLine");

        String line = stripTerminator(validatedLine);
        String[] tokens = split(line);

        // Cheap structural assertions. They do not repeat the automaton's work — they catch
        // the one mistake it cannot, which is a caller that never ran it.
        if (tokens.length != MeterMessage.TOKEN_COUNT) {
            throw new ProtocolException("expected " + MeterMessage.TOKEN_COUNT + " fields, found "
                    + tokens.length + " in: " + line);
        }
        if (!MeterMessage.HEADER.equals(tokens[0])) {
            throw new ProtocolException("expected header '" + MeterMessage.HEADER + "', found '"
                    + tokens[0] + "' in: " + line);
        }

        int deviceId = parseInt(field(tokens[1], MeterMessage.TAG_DEVICE, line), "device id", line);
        long epochMillis = parseLong(field(tokens[2], MeterMessage.TAG_TIMESTAMP, line), "timestamp", line);
        double voltage = parseDecimal(field(tokens[3], MeterMessage.TAG_VOLTAGE, line),
                "voltage", MAX_DECIMAL_6_2, line);
        double current = parseDecimal(field(tokens[4], MeterMessage.TAG_CURRENT, line),
                "current", MAX_DECIMAL_6_2, line);
        double power = parseDecimal(field(tokens[5], MeterMessage.TAG_POWER, line),
                "power", MAX_DECIMAL_10_2, line);

        if (deviceId <= 0) {
            throw new ProtocolException("device id must be positive, was " + deviceId + " in: " + line);
        }

        return Reading.fromEpochMillis(deviceId, epochMillis, voltage, current, power);
    }

    /** Removes the frame terminator if the caller kept it. */
    private static String stripTerminator(String line) {
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == MeterMessage.TERMINATOR) {
            end--;
        }
        return line.substring(0, end);
    }

    /**
     * Splits on the delimiter by scanning, rather than {@code String.split}, because the
     * delimiter {@code '|'} is regex alternation and would have to be escaped — and because
     * a scan does not compile a pattern on a path that runs once per reading per meter.
     */
    private static String[] split(String line) {
        int fields = 1;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == MeterMessage.DELIMITER) {
                fields++;
            }
        }

        String[] tokens = new String[fields];
        int index = 0;
        int start = 0;
        for (int i = 0; i <= line.length(); i++) {
            if (i == line.length() || line.charAt(i) == MeterMessage.DELIMITER) {
                tokens[index++] = line.substring(start, i);
                start = i + 1;
            }
        }
        return tokens;
    }

    /** Strips and checks a field's leading tag, returning the digits behind it. */
    private static String field(String token, char expectedTag, String line) throws ProtocolException {
        if (token.isEmpty() || token.charAt(0) != expectedTag) {
            throw new ProtocolException("expected field tag '" + expectedTag + "', found '" + token
                    + "' in: " + line);
        }
        return token.substring(1);
    }

    private static int parseInt(String text, String field, String line) throws ProtocolException {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new ProtocolException(field + " '" + text + "' does not fit a 32-bit integer in: "
                    + line, e);
        }
    }

    private static long parseLong(String text, String field, String line) throws ProtocolException {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new ProtocolException(field + " '" + text + "' does not fit a 64-bit integer in: "
                    + line, e);
        }
    }

    private static double parseDecimal(String text, String field, double max, String line)
            throws ProtocolException {
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new ProtocolException(field + " '" + text + "' is not a number in: " + line, e);
        }
        if (value > max) {
            throw new ProtocolException(field + " " + value + " exceeds the " + max
                    + " its column can store, in: " + line);
        }
        return value;
    }
}
