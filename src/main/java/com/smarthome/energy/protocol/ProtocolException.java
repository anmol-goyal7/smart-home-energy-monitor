package com.smarthome.energy.protocol;

/**
 * Checked exception raised when a meter message cannot be validated or parsed.
 *
 * <p>Thrown by {@link MessageParser} when a syntactically valid line still carries a
 * value outside the acceptable numeric range. The server's {@code ClientHandler} catches
 * it, logs the offending line, and continues serving the connection rather than tearing
 * it down.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (checked exceptions).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public class ProtocolException extends Exception {
    // Placeholder — constructors implemented by the author.
}
