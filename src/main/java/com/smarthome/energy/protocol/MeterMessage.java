package com.smarthome.energy.protocol;

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
