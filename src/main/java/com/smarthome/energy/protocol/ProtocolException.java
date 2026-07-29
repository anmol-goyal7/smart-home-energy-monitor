package com.smarthome.energy.protocol;

/**
 * Checked exception raised when a meter message cannot be validated or parsed.
 *
 * <p>Thrown by {@link MessageParser} when a syntactically valid line still carries a
 * value outside the acceptable numeric range. The server's {@code ClientHandler} catches
 * it, logs the offending line, and continues serving the connection rather than tearing
 * it down.</p>
 *
 * <p>It is checked, where the persistence layer's {@code DataAccessException} is not,
 * because here the caller genuinely can act: skipping the frame and reading the next one is
 * a complete recovery, and having the compiler insist that decision is written down at the
 * call site is the point.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals (checked exceptions).</p>
 *
 * @author Anmol Goyal (anmol-goyal7)
 */
public class ProtocolException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what was wrong with the message, including the offending value
     */
    public ProtocolException(String message) {
        super(message);
    }

    /**
     * @param message what was wrong with the message
     * @param cause   the underlying failure, typically a {@link NumberFormatException}
     */
    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
