package com.smarthome.energy.protocol;

import com.smarthome.energy.model.Reading;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the parser's two jobs: extracting the five fields from a line the DFA has already
 * accepted, and refusing the values that are syntactically legal but unusable.
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
class MessageParserTest {

    private final MessageParser parser = new MessageParser();

    @Test
    @DisplayName("every field is extracted with the right type and units")
    void extractsAllFields() throws ProtocolException {
        Reading reading = parser.parse("RDG|D3|T1721817600000|V228.40|I4.10|P998.20");

        assertEquals(3, reading.getDeviceId());
        assertEquals(Instant.ofEpochMilli(1_721_817_600_000L), reading.getReadingTimestamp());
        assertEquals(228.40, reading.getVoltage(), 1e-9);
        assertEquals(4.10, reading.getCurrent(), 1e-9);
        assertEquals(998.20, reading.getPowerWatts(), 1e-9);
    }

    @Test
    @DisplayName("the terminator is optional, because the reader may already have stripped it")
    void acceptsFrameWithOrWithoutTerminator() throws ProtocolException {
        String line = "RDG|D3|T1721817600000|V228.40|I4.10|P998.20";
        assertEquals(parser.parse(line), parser.parse(line + "\n"));
    }

    @Test
    @DisplayName("formatting and parsing round-trip a reading unchanged")
    void roundTripsThroughTheFormatter() throws ProtocolException {
        Reading original = Reading.fromEpochMillis(4, 1_721_817_600_123L, 231.25, 12.34, 2854.60);
        assertEquals(original, parser.parse(MeterMessage.format(original)));
    }

    @Test
    @DisplayName("device id zero is legal to the grammar but is not a device")
    void rejectsDeviceIdZero() {
        ProtocolException thrown = assertThrows(ProtocolException.class,
                () -> parser.parse("RDG|D0|T1721817600000|V228.40|I4.10|P998.20"));
        assertTrue(thrown.getMessage().contains("device id must be positive"), thrown.getMessage());
    }

    @Test
    @DisplayName("a device id too wide for an int is reported, not overflowed")
    void rejectsOversizedDeviceId() {
        ProtocolException thrown = assertThrows(ProtocolException.class,
                () -> parser.parse("RDG|D99999999999|T1721817600000|V228.40|I4.10|P998.20"));
        assertTrue(thrown.getMessage().contains("32-bit"), thrown.getMessage());
    }

    @Test
    @DisplayName("a timestamp too wide for a long is reported, not overflowed")
    void rejectsOversizedTimestamp() {
        ProtocolException thrown = assertThrows(ProtocolException.class,
                () -> parser.parse("RDG|D3|T" + "9".repeat(30) + "|V228.40|I4.10|P998.20"));
        assertTrue(thrown.getMessage().contains("64-bit"), thrown.getMessage());
    }

    @Test
    @DisplayName("a voltage wider than DECIMAL(6,2) is refused before it reaches the insert")
    void rejectsVoltageWiderThanItsColumn() {
        ProtocolException thrown = assertThrows(ProtocolException.class,
                () -> parser.parse("RDG|D3|T1721817600000|V123456.78|I4.10|P998.20"));
        assertTrue(thrown.getMessage().contains("voltage"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("column"), thrown.getMessage());
    }

    @Test
    @DisplayName("the widest value each column can hold is still accepted")
    void acceptsColumnMaxima() throws ProtocolException {
        Reading reading = parser.parse("RDG|D3|T1721817600000|V9999.99|I9999.99|P99999999.99");
        assertEquals(9_999.99, reading.getVoltage(), 1e-9);
        assertEquals(99_999_999.99, reading.getPowerWatts(), 1e-9);
    }

    @Test
    @DisplayName("a line that was never validated fails safely instead of misreading fields")
    void rejectsStructurallyWrongInput() {
        assertThrows(ProtocolException.class, () -> parser.parse("nonsense"));
        assertThrows(ProtocolException.class, () -> parser.parse(""));
        assertThrows(ProtocolException.class,
                () -> parser.parse("RDG|X3|T1721817600000|V228.40|I4.10|P998.20"));
        assertThrows(ProtocolException.class,
                () -> parser.parse("EVT|D3|T1721817600000|V228.40|I4.10|P998.20"));
    }
}
