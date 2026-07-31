/**
 * Deliberately-broken code, kept next to its correction, so the fixes can be demonstrated
 * rather than asserted.
 *
 * <p>Every non-obvious defensive decision in this system — the atomic counters, the
 * {@code SwingWorker}, the {@code PreparedStatement}s, the single transaction around a
 * reading and its events — is there because the alternative fails. Saying so is cheap.
 * {@link com.smarthome.energy.demo.FailureDemos} runs both versions and prints what actually
 * happens, which is the difference between a claim and evidence.</p>
 *
 * <p>The broken paths live here and nowhere else. Nothing in {@code server}, {@code client},
 * {@code db}, or {@code rules} depends on this package, and none of the unsafe code in it is
 * reachable from the running system — it is reproduced here, deliberately, rather than left
 * switchable in the classes that do the real work.</p>
 *
 * <p>Syllabus mapping: Unit I — concurrency (lost update); Unit II — the event dispatch
 * thread (frozen UI); Unit III — JDBC (injection, transactions).</p>
 */
package com.smarthome.energy.demo;
