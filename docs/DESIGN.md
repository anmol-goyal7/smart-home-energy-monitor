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

## Why the dispatcher's queue is bounded, and drops when it fills

Putting a queue between the socket readers and their consumers raises the question of what
happens when the consumers cannot keep up — a database that has gone away, or a dashboard
that has stopped reading its socket. An unbounded queue is the tempting answer because it
never refuses anything, but it does not actually preserve those readings: it converts a
visible problem into an out-of-memory failure twenty minutes later, at which point the
server stops recording anything at all and the operator has an unreadable heap dump instead
of a symptom. Blocking the producer is the other option, and it is worse for this system in
particular, because the producer is a socket read loop: back-pressure there stops the server
draining its TCP buffers, which stops the meters sending, which means the readings are lost
anyway and the failure has spread to every appliance rather than staying with the sink that
caused it. So the queue is bounded, overflow drops the newest arrival, and every drop is
counted and reported in the server's status line. The tradeoff is real — under sustained
overload this system loses readings on purpose — but it loses them in a way that is
measurable, bounded, and confined to the moment of overload, which for monitoring telemetry
sampled once a second is a far better failure than either alternative.

The same argument, one level down, is why each dashboard subscriber gets its own bounded
outbox and its own writer thread. A socket write blocks once the receiver stops draining,
so writing to subscribers directly from a dispatcher worker would let a dashboard paused in
a debugger stall the worker, back up the shared queue, and take the database writes down
with it. A slow subscriber should cost that subscriber its frames and nothing else.

## Why the dashboard live feed reuses the meter wire format

The server could publish to dashboards in any encoding it liked — the two ends are both ours
— and a richer format (JSON, or a binary frame carrying the device name alongside the
reading) would let the dashboard render a little more without asking the database. It uses
the meter format instead, with a one-line `SUBSCRIBE`/`OK` handshake in front of it. The
reason is that a second format means a second grammar, a second parser, a second set of
tests, and a second thing to remember to change when a field is added — and the two would be
kept in step by discipline rather than by the compiler. Reusing the meter frames means the
dashboard decodes its feed with the same `WireFormatValidator` and `MessageParser` the
server validates ingest with, so the format is defined once and verified once. The cost is
that the feed inherits the meter format's limits: it can carry a reading and nothing else,
so alerts will need a second frame type in Phase 3 rather than an extra field. That is the
price of the constraint, and it is a smaller price than two parsers.
