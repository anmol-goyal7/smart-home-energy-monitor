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

## Why persistence and detection are one dispatcher sink, not two

The dispatcher exists to fan a reading out to independent consumers, and "store it" and
"evaluate it" read as exactly that: two concerns, two sinks. They are one sink because of a
foreign key. An event's `triggering_reading_id` points at the row the reading insert
generates, so detection cannot write an event until persistence has handed back the key —
and the two writes have to be in the same transaction, or a crash between them leaves the
database holding an alert about a reading that was rolled back, or a reading whose alert was
lost. Two sinks would mean two connections, two transactions, and no way to relate them; the
only thing that would recover the ordering is a shared connection, at which point they are
one consumer wearing two names. The tradeoff is that the sink does more than one thing and
its name says so (`persistence+detection`), against a structure that looks tidier in the
diagram and cannot be made atomic. Atomicity wins: the whole reason the events table has that
foreign key is so an alert can be traced back to the measurement that caused it.

The rule engine itself stays out of this. It is a pure function from a reading to the alerts
it deserves — no DAO, no connection, no transaction — and the sink is what owns the writing.
That keeps the detection logic testable without a database and keeps every decision about
transactions in one class rather than distributed across three rules.

One consequence is worth stating plainly: because the thresholds live in the database,
`--no-persistence` means no detection either. Giving the engine hard-coded fallback limits
would keep the feature alive in that mode, at the price of a server that raises alerts
against numbers nobody configured and that differ from the ones the dashboard displays. A
diagnostic mode that quietly changes what counts as an alert is worse than one that says it
detects nothing.

## Why alerts are published only after the commit

The sink could offer its alerts to the dashboards the moment the rules fire, a few
milliseconds earlier than it does. It waits for the commit instead, because an operator who
sees an alert on screen will go looking for it in the alert log — and a transaction that
rolls back after the alert was published leaves them looking for something that, as far as
every record in the system is concerned, never happened. The cost is those milliseconds and a
failure mode of its own: if the process dies between the commit and the publish, the alert is
in the database and never reached the screen. That is the better failure. A missing alert on a
live feed is recovered by the dashboard's next refresh from the `events` table; an alert that
exists only on a screen is recovered by nothing.

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

### What that second frame type actually cost

Phase 3 paid the bill, and it came to about forty lines: an `ALT` header, five more tagged
fields, a formatter beside the existing one, and a `parseAlert` beside the existing parser —
all in the same two classes, so there is still one file that declares every string this
system puts on a wire. Two things are worth recording about how it was paid.

The alert frame is **not** run through the DFA. The automaton recognises the meter grammar,
which is the language the untrusted side of the system speaks — a socket anyone can connect
to and send anything down. Alert frames originate in this server, so extending the automaton
to a second language would have doubled the thing whose correctness the fuzz comparison
exists to establish, in exchange for validating input this process wrote itself. They are
checked field by field in the parser instead, which is not a shortcut: `parseAlert` rejects a
wrong header, an unknown event type or severity, a non-numeric value, and a device id of
zero. "Both ends are ours" is a statement about intent, not about what arrives on a socket.

Alerts also share the readings' connection rather than getting one of their own. A second
channel would let the alert about a 264 V reading overtake the 264 V itself and colour a tile
for a value it was not displaying. One ordered stream makes that unrepresentable, and it
costs nothing: the alert is one more frame in the same per-subscriber outbox, dropped by the
same rule if a subscriber has fallen far enough behind to be losing readings anyway.
