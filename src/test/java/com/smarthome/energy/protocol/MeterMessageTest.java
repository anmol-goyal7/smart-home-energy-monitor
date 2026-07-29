package com.smarthome.energy.protocol;

import com.smarthome.energy.model.Reading;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the formatter — the producer half of the wire contract.
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
class MeterMessageTest {

    private final Locale originalLocale = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    @Test
    @DisplayName("a reading is rendered as the documented frame, terminator included")
    void formatsTheDocumentedFrame() {
        Reading reading = Reading.fromEpochMillis(3, 1_721_817_600_000L, 228.40, 4.10, 998.20);
        assertEquals("RDG|D3|T1721817600000|V228.40|I4.10|P998.20\n", MeterMessage.format(reading));
    }

    @Test
    @DisplayName("decimal fields always carry exactly two fraction digits")
    void padsAndTruncatesToTwoDecimals() {
        Reading reading = Reading.fromEpochMillis(1, 0L, 230.0, 0.5, 1234.567);
        assertEquals("RDG|D1|T0|V230.00|I0.50|P1234.57\n", MeterMessage.format(reading));
    }

    @Test
    @DisplayName("the frame is the same under a locale that writes decimals with a comma")
    void isIndependentOfTheDefaultLocale() {
        Reading reading = Reading.fromEpochMillis(3, 1_721_817_600_000L, 228.40, 4.10, 998.20);
        String underRoot = MeterMessage.format(reading);

        Locale.setDefault(Locale.FRANCE);
        String underFrance = MeterMessage.format(reading);

        assertEquals(underRoot, underFrance);
        assertTrue(new WireFormatValidator().accepts(underFrance),
                "a comma decimal separator would leave the frame outside the language");
    }

    @Test
    @DisplayName("a value the grammar cannot express is refused at the source")
    void refusesValuesTheGrammarCannotCarry() {
        assertThrows(IllegalArgumentException.class, () -> MeterMessage.format(
                Reading.fromEpochMillis(1, 0L, -1.0, 1.0, 1.0)));
        assertThrows(IllegalArgumentException.class, () -> MeterMessage.format(
                Reading.fromEpochMillis(1, 0L, 230.0, Double.NaN, 1.0)));
        assertThrows(IllegalArgumentException.class, () -> MeterMessage.format(
                Reading.fromEpochMillis(1, 0L, 230.0, 1.0, Double.POSITIVE_INFINITY)));
    }

    @Test
    @DisplayName("the constants the two sides share are the ones the format is built from")
    void exposesTheGrammarConstants() {
        assertEquals("RDG", MeterMessage.HEADER);
        assertEquals('|', MeterMessage.DELIMITER);
        assertEquals('\n', MeterMessage.TERMINATOR);
        assertEquals(6, MeterMessage.TOKEN_COUNT);
    }
}
