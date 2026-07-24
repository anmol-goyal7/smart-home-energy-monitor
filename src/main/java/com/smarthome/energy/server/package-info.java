/**
 * The multithreaded TCP ingest server: the accept loop, the thread-per-client handler, the
 * dispatcher that fans readings out to persistence/rules/dashboard, and the live-feed
 * publisher.
 *
 * <p>This package is the concurrency core of the project — one thread per connected meter,
 * with rule evaluation kept off the socket read path.</p>
 *
 * <p>Syllabus mapping: Unit I — Java OOP fundamentals, TCP sockets, and multithreading.</p>
 */
package com.smarthome.energy.server;
