package com.smarthome.energy.protocol;

import com.smarthome.energy.model.Event;
import com.smarthome.energy.model.EventType;
import com.smarthome.energy.model.Reading;
import com.smarthome.energy.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the second frame type the live feed carries: the {@code ALT} alert frame that
 * {@code DESIGN.md} predicted reusing the meter format would eventually cost.
 *
 * <p>The frame is written by the server and read by the dashboard, so the round trip is the
 * property that matters — but the parser is tested against malformed input too, because
 * "both ends are ours" is a statement about intent and not about what arrives on a socket.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
class AlertFrameTest {

    private static final Instant DETECTED_AT = Instant.ofEpochMilli(1_721_817_600_000L);
    private final MessageParser parser = new MessageParser();

    private static Event alert(String detail) {
        return new Event(3, null, EventType.VOLTAGE_SPIKE, Severity.CRITICAL,
                264.0, 253.0, detail, DETECTED_AT);
    }

    @Test
    @DisplayName("an alert frame renders exactly as documented")
    void framesLookLikeTheDocumentedExample() {
        String frame = MeterMessage.formatAlert(alert("supply at 264.00 V"));

        assertEquals("ALT|D3|T1721817600000|EVOLTAGE_SPIKE|SCRITICAL|M264.00|L253.00|Xsupply at 264.00 V\n",
                frame);
    }

    @Test
    @DisplayName("everything the alert log displays survives the round trip")
    void roundTripsThroughTheWire() throws ProtocolException {
        Event original = alert("Living Room HVAC: supply at 264.00 V, above the 253.00 V ceiling");

        Event decoded = parser.parseAlert(MeterMessage.formatAlert(original));

        assertEquals(original.getDeviceId(), decoded.getDeviceId());
        assertEquals(original.getType(), decoded.getType());
        assertEquals(original.getSeverity(), decoded.getSeverity());
        assertEquals(original.getMeasuredValue(), decoded.getMeasuredValue(), 0.005);
        assertEquals(original.getThresholdValue(), decoded.getThresholdValue(), 0.005);
        assertEquals(original.getDetail(), decoded.getDetail());
        assertEquals(original.getDetectedAt(), decoded.getDetectedAt());
    }

    @Test
    @DisplayName("the database key is deliberately not on the wire")
    void theReadingIdDoesNotCross() throws ProtocolException {
        Event stored = alert("detail").withTriggeringReadingId(4_242L);

        assertNull(parser.parseAlert(MeterMessage.formatAlert(stored)).getTriggeringReadingId());
    }

    @Test
    @DisplayName("a delimiter inside the detail is neutralised rather than breaking the framing")
    void detailCannotBreakTheFrame() throws ProtocolException {
        Event awkward = alert("device A|B tripped\nagain");

        Event decoded = parser.parseAlert(MeterMessage.formatAlert(awkward));

        assertEquals("device A B tripped again", decoded.getDetail());
    }

    @Test
    @DisplayName("an absent detail survives as an absent detail, not as an empty string")
    void nullDetailStaysNull() throws ProtocolException {
        assertNull(parser.parseAlert(MeterMessage.formatAlert(alert(null))).getDetail());
    }

    @Test
    @DisplayName("reading frames and alert frames are told apart by their header")
    void alertFramesAreDistinguishable() {
        Reading reading = new Reading(3, DETECTED_AT, 228.4, 4.1, 998.2);

        assertTrue(MeterMessage.isAlertFrame(MeterMessage.formatAlert(alert("x"))));
        assertFalse(MeterMessage.isAlertFrame(MeterMessage.format(reading)));
        assertFalse(MeterMessage.isAlertFrame(null));
        assertFalse(MeterMessage.isAlertFrame("ALTERNATIVE"), "the delimiter is part of the test");
    }

    @Test
    @DisplayName("the frame is written under Locale.ROOT, whatever the machine's locale is")
    void decimalsDoNotFollowTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            assertTrue(MeterMessage.formatAlert(alert("x")).contains("M264.00"),
                    "a comma decimal separator here would be rejected by the parser on the far side");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("malformed alert frames are rejected, not guessed at")
    void malformedFramesAreRejected() {
        assertThrows(ProtocolException.class, () -> parser.parseAlert("ALT|D3|T1|EVOLTAGE_SPIKE"),
                "too few fields");
        assertThrows(ProtocolException.class,
                () -> parser.parseAlert("RDG|D3|T1|EVOLTAGE_SPIKE|SCRITICAL|M264.00|L253.00|X"),
                "wrong header");
        assertThrows(ProtocolException.class,
                () -> parser.parseAlert("ALT|D3|T1|EMETEOR_STRIKE|SCRITICAL|M264.00|L253.00|X"),
                "an event type this system does not have");
        assertThrows(ProtocolException.class,
                () -> parser.parseAlert("ALT|D3|T1|EVOLTAGE_SPIKE|SFATAL|M264.00|L253.00|X"),
                "a severity this system does not have");
        assertThrows(ProtocolException.class,
                () -> parser.parseAlert("ALT|D0|T1|EVOLTAGE_SPIKE|SCRITICAL|M264.00|L253.00|X"),
                "device id zero");
        assertThrows(ProtocolException.class,
                () -> parser.parseAlert("ALT|D3|T1|EVOLTAGE_SPIKE|SCRITICAL|Mabc|L253.00|X"),
                "a measured value that is not a number");
    }

    @Test
    @DisplayName("a frame parses with or without its terminator")
    void terminatorIsOptionalOnTheWayIn() throws ProtocolException {
        String frame = MeterMessage.formatAlert(alert("x"));
        String stripped = frame.substring(0, frame.length() - 1);

        assertEquals(parser.parseAlert(frame).getDetail(), parser.parseAlert(stripped).getDetail());
    }
}
