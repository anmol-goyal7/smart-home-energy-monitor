# Design decisions

This document records the rationale behind the project's non-obvious structural choices.
Each decision states the tradeoff in both directions and then the reason for the choice at
this project's scale. It exists to support the design-justification questions in the viva.

## Why TCP over UDP for the meter streams

A meter reading is only useful if it arrives intact and in order — a corrupted or
reordered voltage sample would produce a false alert or a misleading chart. TCP gives
ordered, reliable, connection-oriented delivery for free, and its stream model fits a
line-delimited protocol naturally; the cost is per-connection state, handshake latency, and
head-of-line blocking under loss. UDP would be lighter and would suit very high-frequency
telemetry where an occasional dropped sample is harmless and the newest value always
supersedes the last, but it would force us to rebuild framing, ordering, and
loss-recovery on top of it — reinventing what TCP already provides. At this project's
modest reading rate (roughly one reading per appliance per second) the overhead of TCP is
negligible and its guarantees remove a whole class of bugs, so TCP is the right default.

## Why thread-per-client over a thread pool at this scale

The system has a small, bounded number of meters — on the order of ten simulated
appliances — each holding a long-lived connection that is idle most of the time. A dedicated
thread per connection makes the code the simplest possible expression of the design: a
blocking read loop per meter, with no shared executor, no task queue, and complete isolation
so one slow or stalled meter blocks only its own thread. The tradeoff is that threads are a
finite, relatively heavy resource, so this model does not scale to thousands of connections;
there a fixed thread pool (or non-blocking NIO) would cap resource use and amortise thread
creation, at the price of more complex, harder-to-reason-about code and the risk of one
long task starving others in the pool. Because our connection count is small and fixed,
thread-per-client is well within safe limits and buys clarity we would otherwise pay for in
complexity, so it is the deliberate choice here — with the understanding that a
production-scale rollout would revisit it.

## Why MySQL over flat files

The data is inherently relational — readings reference devices, events reference both
readings and devices, thresholds are scoped per device — and it is queried in ways flat
files serve poorly: "this device over this time window", "the most recent alerts", "average
power by hour". A relational database gives us indexed range queries, foreign-key integrity,
concurrent writers from multiple handler threads, and the same data readable by an entirely
separate Python process, all as built-in guarantees. The tradeoff is an external dependency
to install, configure, and keep running, plus JDBC boilerplate. Flat files (CSV or
append-only logs) would need no server and would be trivial to start with, but every query,
every concurrency guard, and every integrity check would become our code to write and get
right. Since the schema is small and the query patterns are exactly what SQL is good at,
MySQL removes more work than it adds — and it is also the syllabus vehicle for the JDBC unit.

## Why the rule engine is separate from the ingest path

Ingesting readings and evaluating them for anomalies are two concerns with different
performance profiles: ingest must keep up with every meter continuously, while evaluation is
bursty (it does most of its work precisely when many readings cross thresholds at once). By
placing the `RuleEngine` behind the `ReadingDispatcher` rather than inside the
`ClientHandler` read loop, a surge of alert processing can never back-pressure or stall the
socket reads that feed it. The separation also keeps each part independently testable and
lets detection rules evolve without touching networking code. The tradeoff is a small amount
of extra indirection and a handoff (a queue/worker) between reading and evaluation, which
adds latency measured in milliseconds and a little more moving machinery than a single
inline call. For a monitoring system where responsiveness of ingest matters more than
shaving milliseconds off detection latency, decoupling the two is the sounder structure.
