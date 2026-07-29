package com.smarthome.energy.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted cases for the validating DFA — the boundaries the fuzzer in
 * {@link WireFormatFuzzTest} is unlikely to hit by chance, plus the error-locating
 * diagnostic, which the fuzzer does not check at all because the reference regex has
 * nothing to compare it against.
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
class WireFormatValidatorTest {

    private static final String VALID = "RDG|D3|T1721817600000|V228.40|I4.10|P998.20";

    private final WireFormatValidator validator = new WireFormatValidator();

    @Test
    @DisplayName("a well-formed, terminated frame is accepted")
    void acceptsWellFormedFrame() {
        assertTrue(validator.accepts(VALID + "\n"));
    }

    @Test
    @DisplayName("the same frame without its terminator is accepted by validateLine")
    void acceptsLineWithoutTerminator() {
        assertTrue(validator.validateLine(VALID).isAccepted());
    }

    @Test
    @DisplayName("a frame without its terminator is not in the language")
    void rejectsFrameMissingTerminator() {
        WireFormatValidator.ValidationResult result = validator.validate(VALID);
        assertFalse(result.isAccepted());
        assertTrue(result.isTruncated());
        assertEquals(VALID.length(), result.getErrorIndex());
        assertEquals(WireFormatValidator.State.S24, result.getState());
    }

    @Test
    @DisplayName("anything after the terminator is rejected from the accepting state")
    void rejectsTrailingCharacters() {
        WireFormatValidator.ValidationResult result = validator.validate(VALID + "\nx");
        assertFalse(result.isAccepted());
        assertEquals(WireFormatValidator.State.S25, result.getState());
        assertEquals("end of input", result.getExpected());
        assertEquals(VALID.length() + 1, result.getErrorIndex());
    }

    @Test
    @DisplayName("empty input stops in the start state")
    void rejectsEmptyInput() {
        WireFormatValidator.ValidationResult result = validator.validateLine("");
        assertFalse(result.isAccepted());
        assertEquals(0, result.getErrorIndex());
        assertEquals(WireFormatValidator.State.S0, result.getState());
        assertEquals("'R'", result.getExpected());
    }

    @Test
    @DisplayName("the header alone is not a frame")
    void rejectsHeaderOnly() {
        assertFalse(validator.validateLine("RDG").isAccepted());
    }

    @Test
    @DisplayName("a decimal field needs at least one digit after the point")
    void rejectsMissingFractionDigit() {
        WireFormatValidator.ValidationResult result =
                validator.validateLine("RDG|D3|T1721817600000|V228.|I4.10|P998.20");
        assertFalse(result.isAccepted());
        assertEquals(WireFormatValidator.State.S13, result.getState());
        assertEquals("digit", result.getExpected());
    }

    @ParameterizedTest
    @DisplayName("structurally broken frames are rejected")
    @ValueSource(strings = {
            "RDG|D|T1721817600000|V228.40|I4.10|P998.20",          // no device digits
            "RDG|D3|T|V228.40|I4.10|P998.20",                      // no timestamp digits
            "RDG|D3|T1721817600000|V.40|I4.10|P998.20",            // no integer part
            "RDG|D3|T1721817600000|V228|I4.10|P998.20",            // voltage has no fraction
            "RDG|D3|T1721817600000|V228.40|I4.10",                 // power field missing
            "RDG|D3|T1721817600000|V228.40|I4.10|P998.20|X1.0",    // an extra field
            "rdg|D3|T1721817600000|V228.40|I4.10|P998.20",         // lower-case header
            "RDG|D3|T1721817600000|V-228.40|I4.10|P998.20",        // signed value
            "RDG D3 T1721817600000 V228.40 I4.10 P998.20",         // spaces instead of pipes
            "RDG|D3|T1721817600000|V228.40|I4.10|P998.20\r",       // CRLF line ending
    })
    void rejectsMalformedFrames(String line) {
        assertFalse(validator.validateLine(line).isAccepted(), () -> "should have rejected: " + line);
    }

    @Test
    @DisplayName("the diagnostic names the column, the state, the expectation, and the character")
    void reportsWhereAndWhyItFailed() {
        // This is the example printed in README.md; it is asserted here so the two cannot drift.
        String line = "RDG|D3|T1721817600000|V228.4x|I4.10|P998.20";
        WireFormatValidator.ValidationResult result = validator.validateLine(line);

        assertFalse(result.isAccepted());
        assertEquals(28, result.getErrorIndex());
        assertEquals(WireFormatValidator.State.S14, result.getState());
        assertEquals("digit or '|'", result.getExpected());
        assertEquals('x', result.getRejectedChar());
        assertEquals("col 28: in state S14, expected digit or '|', got 'x'", result.getMessage());
    }

    @Test
    @DisplayName("the caret in the rendered diagram lands under the offending character")
    void caretLinesUpWithTheError() {
        String line = "RDG|D3|T1721817600000|V228.4x|I4.10|P998.20";
        String[] lines = validator.validateLine(line).describe(line).split("\\R");

        assertEquals(3, lines.length);
        assertEquals(line, lines[0]);
        assertEquals(28, lines[1].indexOf('^'));
        assertEquals(line.charAt(lines[1].indexOf('^')), 'x');
    }

    @Test
    @DisplayName("a line that runs out mid-frame reports end of input, not a bad character")
    void reportsTruncationDistinctly() {
        WireFormatValidator.ValidationResult result = validator.validateLine("RDG|D3|T1721817600000|V228.");
        assertTrue(result.isTruncated());
        assertTrue(result.getMessage().endsWith("got end of input"), result.getMessage());
    }

    @Test
    @DisplayName("the trap state is never left")
    void deadStateIsAbsorbing() {
        WireFormatValidator.State dead = WireFormatValidator.next(WireFormatValidator.State.S0, 'x');
        assertTrue(dead.isDead());
        for (char c = 0; c < 128; c++) {
            assertTrue(WireFormatValidator.next(dead, c).isDead(),
                    "the dead state must absorb every character, but " + c + " left it");
        }
    }

    @Test
    @DisplayName("arbitrarily many digits are allowed in every numeric field")
    void acceptsLongNumbers() {
        assertTrue(validator.validateLine("RDG|D" + "9".repeat(40)
                + "|T" + "1".repeat(40)
                + "|V" + "2".repeat(30) + "." + "0".repeat(30)
                + "|I1.0|P1.0").isAccepted());
    }
}
