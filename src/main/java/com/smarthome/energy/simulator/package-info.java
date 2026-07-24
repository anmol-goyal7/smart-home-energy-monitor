/**
 * The meter simulators: TCP clients that stand in for real smart-meter hardware.
 *
 * <p>One simulator per appliance streams readings (with occasional injected anomalies) in
 * the wire format, producing the many concurrent connections the server is built to handle.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals, TCP client sockets, threading.</p>
 */
package com.smarthome.energy.simulator;
