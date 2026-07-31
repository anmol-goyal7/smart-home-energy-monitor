# Smart Home Energy Monitor — design report

**Course:** Advanced Programming Practice (APP)
**Team:** Anmol Goyal, Bhumika Rajput, Jiya Nambiar

This report is framed as a design-thinking narrative — empathize, define, ideate, prototype,
test — because that is the shape the work actually took. The measurements in the *Test*
section are the ones in the README's
[Engineering evidence](../README.md#engineering-evidence); this document is where they are
interpreted rather than tabulated.

[`DESIGN.md`](DESIGN.md) is the companion to this: it records each structural decision and
the tradeoff behind it, decision by decision. This report is the arc — how the problem was
framed, what was rejected, what was built, and what the evidence changed.

---

## 1. Empathize

The system has one user, and imagining that user concretely settled more design arguments
than any amount of feature listing.

They are someone responsible for a house or a small building: they pay the electricity bill,
and occasionally something goes wrong with an appliance and nobody notices until it fails or
the bill arrives. They are not an electrician and not a database administrator. They will
have this dashboard on a second monitor, glanced at rather than studied.

Three things follow from that, and they turn up throughout the design:

**A glance has to be enough.** An operator who has to read a table of numbers to find out
whether anything is wrong will not look. That is why the appliance tiles are colour-coded by
severity, and why the colour is *held for three seconds* after an alert rather than cleared
by the next reading. A sustained fault produces one alert per reading, one second apart; a
tile that cleared on the next reading would flash green between them and spend half of a
genuine fault claiming the appliance was fine.

**"Nothing is wrong" and "I am not receiving anything" must not look alike.** An empty alert
log is good news; an empty alert log because the server is down is the worst possible news
rendered identically. So the dashboard reconnects on its own and reports every transition,
the tiles go grey and say how long an appliance has been silent, and the strip chart drops a
stale appliance out of the whole-home total rather than holding its last value forever.

**They will want to know what it costs, not what it drew.** "The water heater averages
1.8 kW between 18:00 and 21:00" is telemetry. "Those hours cost ₹N a month, and moving the
load to the off-peak band saves ₹M" is something a person can act on. That is why the Python
module has a `cost_model.py` at all, and why it distinguishes deferrable loads — a water
heater can be rescheduled, a refrigerator cannot, and a report that suggests otherwise is one
that gets ignored.

There is a second audience, which it would be dishonest not to name: this is a graded course
project with a viva. That shaped the work too, and mostly in the same direction — a system
whose decisions can be defended out loud is a system whose decisions were written down. Where
the two audiences pulled apart, they are flagged in this document rather than blended.

---

## 2. Define

> Ingest live power readings from a set of per-appliance smart meters, store them durably,
> evaluate every reading against configurable limits in real time, and present both the live
> state and the historical trend to an operator — while remaining defensible, line by line,
> as a piece of engineering.

The academic constraint is that one coherent application had to exercise all five units of
the syllabus: Java OOP and concurrency, GUI programming, JDBC, Python scripting, and formal
languages. That constraint is unusual and worth being honest about, because it is the kind of
thing that produces a system with a bolted-on module per unit.

It did not, and the reason is the choice of problem. Metering telemetry genuinely needs a
wire format (so a grammar and a recogniser are real, not decorative), genuinely needs
concurrency (many meters, one server), genuinely needs a relational store (readings reference
devices, events reference readings), genuinely needs a UI that stays responsive while it
queries, and genuinely benefits from offline analysis in a language suited to it. The unit
mapping fell out of the problem rather than being imposed on it. The one place the seam shows
is the DFA: a regular expression would have been the ordinary engineering answer, and the
hand-written automaton is justified on other grounds (an inspectable transition table and a
free error position) that are real but that nobody would have reached for unprompted.

**Explicit non-goals.** No authentication, no multi-tenancy, no historical data retention
policy, no alerting beyond the screen, no real hardware. Each was cut because it would add
surface without exercising anything the project is being judged on.

---

## 3. Ideate

The junctions where a different choice was genuinely available. Each is argued in full in
[`DESIGN.md`](DESIGN.md); this is the shape of the decision space.

| Junction | Considered | Chosen | Because |
| -------- | ---------- | ------ | ------- |
| Transport | UDP, TCP | TCP | A reading is worth nothing corrupted or reordered, and at one reading per appliance per second TCP's overhead is invisible |
| Framing | JSON, binary, tagged ASCII | Tagged ASCII | Human-readable on the wire, and it makes the grammar a *small* regular language rather than a parser generator's job |
| Validation | Regex, hand-written DFA | DFA | Same language either way; the explicit table is inspectable and yields the error column for free |
| Concurrency | Thread-per-client, fixed pool, NIO | Thread-per-client | Small bounded connection count; see *Test*, where this got measured and the reasoning changed |
| Storage | Flat files, SQLite, MySQL | MySQL | Relational data, concurrent writers, and a second process reading it in another language |
| Detection placement | In the read loop, behind the dispatcher | Behind the dispatcher | A burst of alerts must not back-pressure ingest |
| Persistence + detection | Two sinks, one sink | One sink | The event's foreign key is the reading's generated id, so the pair must share a transaction |
| Overflow policy | Unbounded queue, block, bounded + drop | Bounded + drop | An unbounded queue converts a visible problem into an OOM twenty minutes later; blocking spreads the failure to every meter |
| Dashboard feed format | New format, reuse meter frames | Reuse | One grammar, one parser, verified once — at the known cost of a second frame type for alerts |
| Analytics parallelism | `threading`, `multiprocessing` | `multiprocessing` | The GIL; and the work partitions cleanly by device. See *Test* — the measured speedup is real but sub-linear, and the reason is instructive |

Two rejected ideas are worth recording because rejecting them was not obvious.

**A richer dashboard feed.** Sending JSON to the dashboard would have let it render device
names without touching the database. It was rejected because a second format means a second
grammar, a second parser, and a second set of tests kept in step by discipline rather than by
the compiler. The constraint was accepted knowing it would cost an extra frame type when
alerts arrived in Phase 3 — and [`DESIGN.md`](DESIGN.md) records what that bill came to when
it fell due: about forty lines, in the same two files.

**Hard-coded fallback thresholds.** The rule engine reads its limits from the database, so
`--no-persistence` means no detection. Giving it built-in defaults would keep the feature
alive in that mode, and was rejected: a diagnostic mode that quietly changes what counts as
an alert is worse than one that says plainly it detects nothing.

---

## 4. Prototype

Built in four phases, each of which had to run end to end before the next began.

**Phase 1 — persistence foundation.** Schema, Docker Compose, and one JDBC round trip.
Deliberately unglamorous, and deliberately first: every later phase writes to this schema,
and a foreign key discovered late is a refactor across three packages.

**Phase 2 — the live pipeline.** The wire format and its automaton, the TCP server and its
thread-per-client model, the dispatcher, the simulators, and the Swing dashboard. At the end
of Phase 2 the project was complete against its core specification.

The DFA's randomised equivalence check against a reference regular expression was originally
scheduled for Phase 4 and moved here. The automaton is what every reading passes through;
deferring its verification would have meant building three more phases on an unverified
recogniser.

**Phase 3 — detection and analytics.** The rule engine as a set of `DetectionRule` strategies
over a reloadable `RuleContext`; the single transaction that writes a reading and its events
together; the alert frame; and the Python module.

**Phase 4 — evidence.** The three remaining dashboard panels, the scripted demo scenario, the
four failure-mode demonstrations, and the four measurements. Two pieces of production code
exist only because the evidence needed them, and both are better for it:

- `AcceptStrategy`, which turned the accept loop's one `new Thread(...)` into a pluggable
  choice, so the concurrency model could be *run* both ways rather than argued about.
- The live feed's `RELOAD` command, which the threshold editor needed. The dashboard commits
  to the `thresholds` table over its own JDBC connection, and the running server has no way
  of noticing — its `RuleContext` was loaded at start-up. Polling the table was the
  alternative and was rejected: an edit that takes effect "some time in the next thirty
  seconds" is not something anyone can point at during a demonstration.

---

## 5. Test

Four measurements and four failure demonstrations. Conditions, and the raw output of every
run, are in the README under
[Measurement conditions](../README.md#measurement-conditions); the numbers are reproduced
here only where they are being interpreted.

### 5.1 The concurrency model — where the design was wrong

`DESIGN.md` argued for thread-per-client on grounds of simplicity at small scale, and this
README predicted the measurement would show the two models "indistinguishable at 10 meters,
with the pool pulling ahead as the connection count grows".

The second half of that prediction is wrong, and so is the first. A fixed pool of eight did
not pull ahead at any meter count — it fell behind at every one, including ten.

The reason is not subtle once it is stated, which is what makes it worth having measured. A
thread pool multiplexes many *short* tasks across few threads: each task releases its thread
promptly, so the pool stays available. A `ClientHandler` is the opposite kind of task. It
blocks on a socket read for the entire life of the connection, so submitting it to a pool of
*n* threads does not share *n* threads among many meters — it means meter *n+1* onwards sits
in the queue, connected but never read from, until one of the first *n* disconnects. The pool
bounds the thread count by bounding how many meters are served at all.

The prediction was wrong because it was reasoning about pools in general instead of about
this task. The right conclusion is narrower and more useful than "thread-per-client is fine
at this scale": **a pool is the wrong tool wherever a task holds its thread for the lifetime
of a connection, at any scale.** The pool that this system *does* have — the dispatcher's
workers — is in the right place, because "deliver one reading" is a task that returns.

The latency columns need reading with that in mind, and are a good example of a number that
misleads on its own. The pooled runs' p50 and p99 are *as good as* thread-per-client's, or
better. That is not the pool doing well: the readings that would have been late were never
delivered at all, so they are absent from the distribution rather than in its tail. The
column that tells the truth is "meters actually read", which is why the benchmark reports it
alongside.

### 5.2 The JDBC insert path — where the design was right, expensively

Three strategies, each differing from the one above it in exactly one respect:

| Strategy | Rows/s | vs. baseline |
| -------- | ------ | ------------ |
| Autocommit, one `INSERT` per row (what the system does today) | 201 | 1.0× |
| `PreparedStatement`, batched at 500 | 8,901 | 44× |
| Batched, connections from a pool | 10,351 | 52× |

The baseline is not a straw man: it is exactly what `ReadingDao.insert(Reading)` does on
every call, and what `PersistenceSink` does for every reading that arrives. Two hundred rows
a second against a fleet offering six is roughly thirty times the headroom needed, which is
why the simple version is still there.

What the two deltas separate is worth more than the totals. Batching (row 1 → row 2) is worth
44×, and almost all of it is the per-row commit: 50,000 durable transactions against 100.
Pooling (row 2 → row 3) is worth a further 16%, which is the TCP handshake and MySQL
authentication amortised away. If this system ever needs more insert throughput, that
ordering says where to spend the effort — and it says that the connection pool, which is the
change people reach for first, is the smaller half of it.

### 5.3 The index — the cheapest evidence in the project

The dashboard's history query, planned with and without the composite
`(device_id, reading_ts)` index over ~113,000 stored readings:

| Index | `type` | Rows examined | `Extra` |
| ----- | ------ | ------------- | ------- |
| Composite | `range` | 899 | `Using index condition` |
| Device only | `ref` | 36,240 | `Using where; Using filesort` |

Forty times fewer rows examined, and no sort. Two details had to be got right for this to
mean anything, and both are recorded in `sql/explain_index.sql`:

The index cannot simply be dropped — `fk_readings_device` requires an index on `device_id`,
and the composite one is currently serving that requirement, so `DROP INDEX` fails outright.
The comparison is therefore against a single-column index on `device_id`, which is the
honest counterfactual anyway: the schema would never be without one.

And the window matters. An all-time range makes both plans full table scans, because
selecting every row a device has genuinely *is* cheaper to scan than to seek. That is the
optimiser being right, not the index being useless, and the first version of this measurement
showed it before the query was corrected to the 15-minute window the dashboard actually uses.

### 5.4 Python parallelism — right, and sub-linear for a knowable reason

| Mode | Workers | Wall clock | Speedup |
| ---- | ------- | ---------- | ------- |
| Serial | 1 | 1.47 s | 1.00× |
| `multiprocessing.Pool` | 2 | 1.08 s | 1.36× |
| `multiprocessing.Pool` | 4 | 0.79 s | 1.85× |
| `multiprocessing.Pool` | 8 | 0.69 s | 2.12× |

Real, and clearly flattening. Three things cap it, and naming them is more useful than the
2.12× itself:

1. **There are only six devices.** Eight workers cannot beat six units of work; the last two
   have nothing to do. The 4→8 step measures almost nothing but pool construction.
2. **The work is IO-bound on MySQL**, not CPU-bound. Parallelising it overlaps waiting, and
   the ceiling is how many queries the database will usefully serve at once.
3. **Pool construction is not free**, and on Python 3.14 it is less free than it used to be:
   the default start method on Linux is now `forkserver` rather than `fork`, so each worker
   re-imports the module. The benchmark times the whole operation including pool setup,
   because that is the cost the report actually pays every run.

The GIL argument for `multiprocessing` over `threading` therefore holds, but it is not what
this measurement demonstrates — an IO-bound workload would have parallelised under threads
too. What it demonstrates is that the parallelism is real and that its ceiling is the
database, which is the honest finding.

The benchmark also checks that the pooled run returned the same summaries as the serial one.
A speedup is only interesting if the fast answer is the same answer, and the results cross a
process boundary as a pickle on the way back.

### 5.5 The failure demonstrations

| Demonstration | Broken | Corrected |
| ------------- | ------ | --------- |
| Lost update (8 threads × 200,000 increments) | 666,167 of 1,600,000 — 58% lost | exact |
| Frozen UI (3 s query, 50 ms EDT timer) | longest tick gap 3,282 ms | 50 ms |
| SQL injection (`no such device' OR '1'='1`) | 6 rows of 6 returned | 0 rows |
| Partial write (event violates its foreign key) | 1 orphaned reading | 0 |

The lost-update figure is the interesting one, because it is *not stable*. 58% on this
machine on this run; a different fraction on the next. A defect that loses a different amount
every time is one that no assertion catches and no test suite protects against — which is the
argument for making the counter atomic rather than for writing a test that watches it.

---

## 6. What the evidence changed

Three things, which is the answer to "what was the point of measuring".

**The concurrency argument got better.** It went from "thread-per-client is simpler and we
are small" — true, and weak, because it concedes the model is a compromise — to "a pool
cannot help here, because the task holds its thread for the connection's lifetime". The
second is a reason rather than an excuse, and it is falsifiable.

**The scaling story got specific.** "The database will be fine" became "201 inserts a second,
against six offered, and batching is worth 44× if that changes". The second sentence tells a
maintainer what to do; the first tells them to hope.

**Two defects were found by building the evidence rather than the feature.** The scripted
scenario's threshold-edit step originally raised the refrigerator's ceiling to 520 W while
the appliance drew 540 — committing it would have reloaded the engine correctly and carried
on alerting, in front of the audience. And the step that cues the operator to make that edit
was originally a `NOMINAL` step, which would have dropped the load back to normal at the same
moment, producing an identical screen whether the operator did anything or not. Both are now
asserted by `ScenarioTest`, and neither was visible by reading the timeline.

---

## 7. Limitations

Stated here rather than discovered in the viva.

- **The ingest benchmark runs its load generators in the same JVM as the server.** Both
  compete for the same cores, which caps the absolute throughput figures. The comparison
  between strategies is unaffected — both pay the same cost — but the numbers are "under this
  harness", not a capacity claim.
- **Latency is measured to the millisecond**, because that is the resolution of the meter-side
  timestamp the wire format carries. A p50 of 0 ms means "below what the protocol records",
  not "instantaneous"; the mean, which the benchmark also prints, is the sub-millisecond
  detail.
- **The Python speedup is measured over six devices.** The curve's shape past four workers
  says more about having run out of work than about the pool.
- **`SELECT SLEEP(3)` is not a real query.** The frozen-UI demonstration uses one so that the
  two runs are comparable to the millisecond. The point being demonstrated is which thread
  waits, not what a query costs.
- **The SQL injection demonstration stops at data disclosure.** `'; DROP TABLE …` does not
  fire, because Connector/J refuses multiple statements per `execute` unless
  `allowMultiQueries` is set and this project's URL does not set it. The remaining
  consequence — the attacker choosing what the `WHERE` clause means — is the ordinary one and
  is quite bad enough.
- **Everything runs on one host.** No measurement here says anything about behaviour across a
  real network, where the TCP-versus-UDP argument would actually be tested.

What a production version would revisit, in order: connection pooling and batched inserts on
the persistence path; retention and partitioning for the `readings` table, which grows without
bound; authentication on both sockets, neither of which currently asks who is connecting; and
an alerting channel that is not a screen somebody has to be looking at.
