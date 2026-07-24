package com.smarthome.energy.protocol;

import com.smarthome.energy.model.Reading;

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
 * <p>Intended API:
 * <ul>
 *   <li>{@code Reading parse(String validatedLine)} — returns the reading, or throws
 *       {@link ProtocolException} if a field is out of numeric range.</li>
 * </ul>
 *
 * <p>Syllabus mapping: Unit V — Formal languages &amp; automata (tokenising the accepted
 * string); Unit I — Java OOP fundamentals.</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class MessageParser {
    // Placeholder — field extraction implemented by the author.
}
