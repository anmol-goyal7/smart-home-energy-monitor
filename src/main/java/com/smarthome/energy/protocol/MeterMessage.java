package com.smarthome.energy.protocol;

import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.Reading;

import java.util.Locale;
import java.util.Objects;

/**
 * Constants and helpers describing the on-the-wire meter message format.
 *
 * <p>This class is the single source of truth for the delimiter, the field tags
 * ({@code D}, {@code T}, {@code V}, {@code I}, {@code P}), the {@code RDG} header, and
 * the newline terminator. Both the {@code MeterSimulator} (which formats messages) and
 * the {@link WireFormatValidator}/{@link MessageParser} pair (which read them) refer to
 * these constants so the producer and consumer can never drift apart.</p>
 *
 * <p>One reading is one line:</p>
 *
 * <pre>
 *   RDG|D3|T1721817600000|V228.40|I4.10|P998.20\n
 * </pre>
 *
 * <h2>Why the live feed's handshake lives here too</h2>
 *
 * <p>The dashboard's live feed is a second contract crossing a socket between two
 * processes, and after the handshake it carries reading frames in exactly this format.
 * Keeping {@link #SUBSCRIBE_COMMAND} and {@link #SUBSCRIBE_ACK} beside the frame definition
 * means every string this system puts on a wire is declared in one file, so changing either
 * side is a change to a shared constant rather than to a literal typed out twice.</p>
 *
 * <h2>The alert frame</h2>
 *
 * <p>The same feed carries the rule engine's alerts, which a reading frame cannot express —
 * this is the second frame type {@code DESIGN.md} predicted the reuse of the meter format
 * would cost. It is deliberately built out of the same pieces (the delimiter, one tag per
 * field, the newline terminator) so the two are read by the same kind of code:</p>
 *
 * <pre>
 *   ALT|D1|T1721817600000|EVOLTAGE_SPIKE|SCRITICAL|M264.00|L253.00|Xvoltage 264.00 V above …\n
 * </pre>
 *
 * <p>The detail field is free text and comes last, so it may contain spaces; the delimiter
 * and the terminator are stripped from it on the way out ({@link #formatAlert(Event)}),
 * because a device name or a rule description containing a {@code '|'} would otherwise
 * produce a frame that splits into the wrong number of fields.</p>
 *
 * <p>Alert frames are not run through {@link WireFormatValidator}: the DFA recognises the
 * meter grammar, which is the language the <em>untrusted</em> side of the system speaks.
 * Alerts originate in this server and are checked by {@link MessageParser#parseAlert}, which
 * rejects anything malformed rather than assuming the sender got it right.</p>
 *
 * <p>Syllabus mapping: Unit V — Formal languages &amp; automata (grammar definition).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class MeterMessage {

    /** Literal header that opens every reading frame. */
    public static final String HEADER = "RDG";

    /** Separator between the header and each tagged field. */
    public static final char DELIMITER = '|';

    /** Character that terminates a frame, and so the line separator of the stream. */
    public static final char TERMINATOR = '\n';

    /** Tag introducing the device id field. */
    public static final char TAG_DEVICE = 'D';

    /** Tag introducing the meter-side timestamp field (epoch milliseconds). */
    public static final char TAG_TIMESTAMP = 'T';

    /** Tag introducing the RMS voltage field. */
    public static final char TAG_VOLTAGE = 'V';

    /** Tag introducing the RMS current field. */
    public static final char TAG_CURRENT = 'I';

    /** Tag introducing the real power field. */
    public static final char TAG_POWER = 'P';

    /** Number of {@link #DELIMITER}-separated tokens in a frame: the header plus five fields. */
    public static final int TOKEN_COUNT = 6;

    /** Literal header that opens every alert frame on the dashboard feed. */
    public static final String ALERT_HEADER = "ALT";

    /** Tag introducing the alert's {@code EventType}. */
    public static final char TAG_EVENT_TYPE = 'E';

    /** Tag introducing the alert's {@code Severity}. */
    public static final char TAG_SEVERITY = 'S';

    /** Tag introducing the value that tripped the rule. */
    public static final char TAG_MEASURED = 'M';

    /** Tag introducing the limit that was crossed. */
    public static final char TAG_LIMIT = 'L';

    /** Tag introducing the alert's free-text detail, which is always the last field. */
    public static final char TAG_DETAIL = 'X';

    /** Number of {@link #DELIMITER}-separated tokens in an alert frame: the header plus seven. */
    public static final int ALERT_TOKEN_COUNT = 8;

    /** Fractional digits every decimal field is written with. */
    public static final int DECIMAL_PLACES = 2;

    /** Line a dashboard sends to the live-feed port to start receiving readings. */
    public static final String SUBSCRIBE_COMMAND = "SUBSCRIBE";

    /** Line the server answers a successful {@link #SUBSCRIBE_COMMAND} with. */
    public static final String SUBSCRIBE_ACK = "OK";

    /**
     * Format applied to one decimal field, pinned to {@link Locale#ROOT} on purpose: under a
     * locale such as {@code fr-FR} the default would render {@code 228,40}, which the grammar
     * does not accept — so every meter would be rejected on a machine configured for that
     * locale and nowhere else. Naming the locale removes that failure mode entirely.
     */
    private static final String DECIMAL_FORMAT = "%." + DECIMAL_PLACES + "f";

    private MeterMessage() {
        // Constants and static helpers only.
    }

    /**
     * Renders a reading as a complete frame, terminator included, ready to be written
     * straight to a socket.
     *
     * @param reading the reading to encode; must not be null
     * @return the wire line, ending in {@link #TERMINATOR}
     * @throws NullPointerException     if {@code reading} is null
     * @throws IllegalArgumentException if any measured value is negative or not finite
     */
    public static String format(Reading reading) {
        Objects.requireNonNull(reading, "reading");

        StringBuilder frame = new StringBuilder(64);
        frame.append(HEADER)
                .append(DELIMITER).append(TAG_DEVICE).append(reading.getDeviceId())
                .append(DELIMITER).append(TAG_TIMESTAMP).append(reading.getReadingEpochMillis())
                .append(DELIMITER).append(TAG_VOLTAGE);
        appendDecimal(frame, reading.getVoltage(), "voltage");
        frame.append(DELIMITER).append(TAG_CURRENT);
        appendDecimal(frame, reading.getCurrent(), "current");
        frame.append(DELIMITER).append(TAG_POWER);
        appendDecimal(frame, reading.getPowerWatts(), "power");
        return frame.append(TERMINATOR).toString();
    }

    /**
     * Renders an alert as a complete frame, terminator included.
     *
     * <p>The event's {@code triggeringReadingId} is deliberately left off the wire: it is a
     * database key, and a dashboard running without a database — or against a different one —
     * could do nothing with it but be misled. Everything the alert log displays is carried on
     * the frame itself.</p>
     *
     * @param event the alert to encode; must not be null
     * @return the wire line, ending in {@link #TERMINATOR}
     * @throws NullPointerException     if {@code event} is null
     * @throws IllegalArgumentException if a measured value or limit is negative or not finite
     */
    public static String formatAlert(Event event) {
        Objects.requireNonNull(event, "event");

        StringBuilder frame = new StringBuilder(128);
        frame.append(ALERT_HEADER)
                .append(DELIMITER).append(TAG_DEVICE).append(event.getDeviceId())
                .append(DELIMITER).append(TAG_TIMESTAMP).append(event.getDetectedAt().toEpochMilli())
                .append(DELIMITER).append(TAG_EVENT_TYPE).append(event.getType().name())
                .append(DELIMITER).append(TAG_SEVERITY).append(event.getSeverity().name())
                .append(DELIMITER).append(TAG_MEASURED);
        appendDecimal(frame, event.getMeasuredValue(), "measured value");
        frame.append(DELIMITER).append(TAG_LIMIT);
        appendDecimal(frame, event.getThresholdValue(), "threshold value");
        frame.append(DELIMITER).append(TAG_DETAIL).append(sanitiseDetail(event.getDetail()));
        return frame.append(TERMINATOR).toString();
    }

    /**
     * @param line a line read from the live feed; may be null
     * @return true if it is an alert frame rather than a reading frame
     */
    public static boolean isAlertFrame(String line) {
        return line != null && line.startsWith(ALERT_HEADER + DELIMITER);
    }

    /**
     * Replaces the two characters that would break the framing, so free text stays free.
     *
     * <p>Silently rewriting content is normally the wrong answer, but the alternative here is
     * to refuse to publish an alert because its description contained a pipe — losing the
     * alert to protect its punctuation.</p>
     */
    private static String sanitiseDetail(String detail) {
        if (detail == null || detail.isEmpty()) {
            return "";
        }
        return detail.replace(DELIMITER, ' ').replace(TERMINATOR, ' ').replace('\r', ' ');
    }

    /**
     * Appends one decimal field, refusing values the grammar cannot express.
     *
     * <p>A decimal field is {@code digits '.' digits} — no sign, no exponent. A negative or
     * infinite value would be encoded as something no validator accepts, and the meter would
     * simply look as though it had gone quiet. Failing here instead names the field and the
     * value at the point the mistake was made.</p>
     */
    private static void appendDecimal(StringBuilder out, double value, String field) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("cannot encode " + field + " " + value
                    + ": the wire format carries only finite, non-negative decimals");
        }
        out.append(String.format(Locale.ROOT, DECIMAL_FORMAT, value));
    }
}
