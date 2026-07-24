/**
 * The meter wire protocol: the message format definition, the DFA that validates a raw line
 * before parsing, and the parser that turns an accepted line into a {@code Reading}.
 *
 * <p>Validation and parsing are separated on purpose — the DFA decides whether a line is in
 * the language, the parser extracts its fields — so malformed input is rejected before any
 * numeric parsing runs.</p>
 *
 * <p>Syllabus mapping: Unit V — Formal languages &amp; automata.</p>
 */
package com.smarthome.energy.protocol;
