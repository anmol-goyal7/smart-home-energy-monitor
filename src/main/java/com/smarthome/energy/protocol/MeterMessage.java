package com.smarthome.energy.protocol;

/**
 * Constants and helpers describing the on-the-wire meter message format.
 *
 * <p>This class is the single source of truth for the delimiter, the field tags
 * ({@code D}, {@code T}, {@code V}, {@code I}, {@code P}), the {@code RDG} header, and
 * the newline terminator. Both the {@code MeterSimulator} (which formats messages) and
 * the {@link WireFormatValidator}/{@link MessageParser} pair (which read them) refer to
 * these constants so the producer and consumer can never drift apart.</p>
 *
 * <p>Intended contents:
 * <ul>
 *   <li>{@code HEADER = "RDG"}, {@code DELIMITER = '|'}, {@code TERMINATOR = '\n'}.</li>
 *   <li>tag constants and a {@code format(Reading)} helper for the simulator side.</li>
 * </ul>
 *
 * <p>Syllabus mapping: Unit V — Formal languages &amp; automata (grammar definition).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public final class MeterMessage {
    // Placeholder — format constants and helpers implemented by the author.
}
