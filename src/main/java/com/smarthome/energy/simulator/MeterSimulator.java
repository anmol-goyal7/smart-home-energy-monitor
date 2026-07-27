package com.smarthome.energy.simulator;

/**
 * Simulates one appliance's smart meter: a single TCP client that streams readings.
 *
 * <p>Each {@code MeterSimulator} opens its own connection to the server's meter port and,
 * on a fixed interval, generates a reading from its {@link ApplianceProfile} (via
 * {@link WaveformGenerator}), formats it in the wire format defined by
 * {@code protocol.MeterMessage}, and writes it to the socket. Running one simulator per
 * appliance is what produces the many concurrent connections the multithreaded server is
 * built to handle.</p>
 *
 * <p>The generator occasionally injects out-of-band values (a spike, a sag, an overload)
 * so the rule engine has anomalies to detect during a demo.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals, TCP client sockets, threading.</p>
 *
 * @author Jiya Nambiar (jiyanambiar)
 */
public final class MeterSimulator implements Runnable {

    @Override
    public void run() {
        // Placeholder — connect, generate, format, and stream loop implemented by the author.
    }
}
