# Smart Home Energy Monitor

A monitoring system that ingests live power readings from simulated smart meters, one
per household appliance, persists them, evaluates them for power-quality problems in real
time, and presents both the live stream and historical trends to an operator. A separate
Python module mines the accumulated history for peak-hour demand, per-device usage trends,
and time-of-use cost

This repository is the course project for **Advanced Programming Practice (APP)**. The
system is deliberately built to exercise, in one coherent application, all five
programming paradigms surveyed across the five units of the syllabus.

> **Project status — complete.** All four phases are implemented: the database layer; the
> live pipeline end to end — meter simulators streaming over TCP, the validating DFA, the
> thread-per-client server and its dispatcher, and the Swing dashboard showing the feed;
> detection and analytics — the rule engine raising spike, sag, and overload alerts into the
> dashboard and the `events` table, and the Python multiprocessing analytics with its
> peak-hour report and time-of-use cost model; and the Layer 2 additions — the three
> remaining dashboard panels, the scripted demo scenario, the four failure-mode
> demonstrations, and the four measurements in
> [Engineering evidence](#engineering-evidence).
>
> Every number in the evidence tables was produced by running the harness named above it on
> the machine described in [Measurement conditions](#measurement-conditions); none is
> estimated. Two of them did not come out the way this document predicted, and both
> predictions have been left in place next to the result — see
> [Where the measurements contradicted the design](#where-the-measurements-contradicted-the-design).

---

## Table of contents

1. [Course context and syllabus mapping](#course-context-and-syllabus-mapping)
2. [The problem this system solves](#the-problem-this-system-solves)
3. [Architecture](#architecture)
4. [Feature scope](#feature-scope)
5. [Wire protocol and DFA validation](#wire-protocol-and-dfa-validation)
6. [Database schema](#database-schema)
7. [Rule engine detection logic](#rule-engine-detection-logic)
8. [Dashboard](#dashboard)
9. [Python analytics module](#python-analytics-module)
10. [Engineering evidence](#engineering-evidence)
11. [Failure-mode demonstrations](#failure-mode-demonstrations)
12. [Build and run](#build-and-run)
13. [Directory layout](#directory-layout)
14. [Milestones](#milestones)
15. [Design questions and where they are answered](#design-questions-and-where-they-are-answered)
16. [Team and ownership](#team-and-ownership)
17. [License](#license)

---

## Course context and syllabus mapping

- **Course:** Advanced Programming Practice (APP), 4 credits, 3rd semester
- **Programme:** B.Tech CSE (Software Engineering), SRMIST Kattankulathur, batch 2025–2029
- **Assessment:** 60% project, 20% report and viva; no final written exam (CLA-2 rubric,
  grading up to the Analyze/Evaluate level)

The course surveys five programming paradigms, one per unit. The project touches all five;
each unit maps to a concrete, self-contained component of this system, and each also carries
a **measurement** that supports the Analyze/Evaluate level of the rubric — a design claim is
only made here if a number in [Engineering evidence](#engineering-evidence) backs it.

| Unit | Syllabus topic | Component in this system | Supporting measurement |
| ---- | -------------- | ------------------------ | ---------------------- |
| I | Java OOP fundamentals (classes, interfaces, threading) | The domain model (`model`), the multithreaded ingest server (`server`), the meter simulators (`simulator`), and the strategy-based rule engine (`rules`). | Thread-per-client vs. thread-pool under load |
| II | GUI programming with Swing/AWT | The dashboard (`client`), structured as Model–View–Controller, showing live per-appliance usage, history, alerts, and live threshold editing. | Responsiveness with and without `SwingWorker` |
| III | Database connectivity via JDBC | The persistence layer (`db`): a connection factory plus one DAO per table over MySQL. | Naive vs. batched inserts; `EXPLAIN` with and without the index |
| IV | Python scripting and multiprocessing | The analytics satellite (`python/analytics`), which parallelises per-device trend analysis across a process pool. | Serial vs. `multiprocessing.Pool` speedup |
| V | Formal language / automata | The `WireFormatValidator` (`protocol`): a deterministic finite automaton that validates every meter message before it is parsed. | Randomised equivalence check against a reference regex |

The viva is design-justification heavy, so the rationale behind the non-obvious choices is
documented separately in [`docs/DESIGN.md`](docs/DESIGN.md), and indexed in
[Design questions and where they are answered](#design-questions-and-where-they-are-answered).

---

## The problem this system solves

Household electricity supply is not perfectly clean, and household loads are not perfectly
behaved. Voltage can rise above safe levels (a **spike**) or drop below them (a **sag**),
and an individual appliance can draw more power than its circuit is rated for (an
**overload**). Any of these can damage equipment or indicate a fault, but they are
invisible to a resident who only sees a monthly aggregate bill.

This system makes per-appliance electrical behaviour observable as it happens. Each
appliance has a smart meter that streams voltage, current, and power readings. The server
ingests every stream concurrently, records it, and checks each reading against configured
limits, raising a persisted alert the moment a limit is crossed. An operator watching the
dashboard sees each appliance's live usage, its recent history, and a running log of
alerts. Offline, the Python module answers the longer-horizon questions — when the home
peaks, how each device's consumption is trending, and what the usage actually costs under a
time-of-use tariff — that support load shifting and fault diagnosis.

Because real smart-meter hardware is out of scope for a course project, the meters are
simulated: one TCP client process per appliance, generating realistic readings (with
occasional injected anomalies so the detection path can be demonstrated).

---

## Architecture

The system is four cooperating processes (or process groups): the meter simulators, the
Java server, the Swing dashboard, and the Python analytics job. They communicate over TCP
and through the shared MySQL database.

```mermaid
flowchart TD
    subgraph METERS["Meter simulators - one TCP client per appliance"]
        M1[Refrigerator]
        M2[HVAC]
        M3[Water heater]
        Mn[... others]
    end

    METERS -->|wire messages over TCP| ACCEPT

    subgraph SERVER["Java multithreaded ingest server"]
        ACCEPT[Accept loop] --> HANDLER[ClientHandler - thread per client]
        HANDLER --> VALIDATE{WireFormatValidator DFA}
        VALIDATE -->|rejected| SKIP[Log position and skip]
        VALIDATE -->|accepted| PARSE[MessageParser to Reading]
        PARSE --> DISPATCH[ReadingDispatcher]
        DISPATCH --> SINK[PersistenceSink - one transaction]
        SINK --> RULES[RuleEngine]
    end

    SINK -->|reading + its events| DB[(MySQL)]
    DISPATCH --> PUBLISH[DashboardPublisher]
    SINK -->|alerts, after commit| PUBLISH

    PUBLISH -->|live feed over TCP| DASHBOARD
    DB -->|history over JDBC| DASHBOARD

    subgraph DASHBOARD["Swing dashboard - MVC"]
        FEED[LiveFeedClient] --> CONTROLLER[Controller]
        CONTROLLER --> MODEL[Model]
        MODEL --> VIEWNODE[View]
    end

    DASHBOARD -->|threshold edits over JDBC| DB
    DB -->|read-only history| PYTHON[Python analytics - multiprocessing]
```

Data flow from meter to dashboard, step by step:

1. A **meter simulator** formats a reading into the wire format and writes it to its TCP
   connection.
2. The server's **accept loop** (`EnergyMonitorServer`) has already handed that connection
   to a dedicated **`ClientHandler`** thread — one thread per connected meter.
3. The handler reads a line and passes it to the **`WireFormatValidator`** DFA. Malformed
   lines are rejected with the offending character position, logged, and skipped; the
   connection stays open.
4. An accepted line is turned into a typed `Reading` by **`MessageParser`**.
5. The handler hands the reading to the **`ReadingDispatcher`**, which fans it out to its
   sinks without the handler needing to know about any of them:
   - the **`DashboardPublisher`** pushes it to any subscribed dashboards;
   - the **`PersistenceSink`** stores it and evaluates it (both off the socket read path).
6. Inside that sink, and inside one transaction: `ReadingDao` inserts the reading, the
   **`RuleEngine`** evaluates it against the cached thresholds, and `EventDao` inserts any
   `Event` raised — bound to the `reading_id` the insert just generated. The transaction
   commits, and only then are the alerts forwarded to the dashboard's alert channel as
   `ALT` frames on the live feed. Persistence and detection share one sink because they
   share one transaction; the reasoning is in [`docs/DESIGN.md`](docs/DESIGN.md).
7. The **dashboard** shows the live stream (from the publisher over TCP) alongside history
   and past alerts (queried from MySQL over JDBC), and can write threshold changes back.
8. Independently, the **Python analytics** job reads the accumulated history from MySQL and
   produces its report.

The rationale for the key structural decisions — TCP vs UDP, thread-per-client vs a pool,
MySQL vs flat files, and keeping the rule engine off the ingest path — is in
[`docs/DESIGN.md`](docs/DESIGN.md).

---

## Feature scope

The scope is deliberately split into two layers. **Layer 1 is the graded core** — the system
described in the sections above, running end to end. **Layer 2** is a set of additions chosen
against one rule: each must be explainable in two sentences by the person who wrote it, and
each must either demonstrate a syllabus concept directly or produce evidence for a design
claim. Cleverness that cannot be reconstructed under questioning was deliberately left out.

### Layer 1 — core pipeline

| # | Capability | Package |
| - | ---------- | ------- |
| 1 | Meter simulators streaming realistic readings over TCP, with injectable anomalies | `simulator` |
| 2 | DFA validation of every line before parsing | `protocol` |
| 3 | Thread-per-client ingest server with a fan-out dispatcher | `server` |
| 4 | JDBC persistence of readings and events over MySQL | `db` |
| 5 | Strategy-based rule engine raising spike / sag / overload events | `rules` |
| 6 | Swing MVC dashboard: live per-appliance usage, history, alert log | `client` |
| 7 | Python multiprocessing analytics: peak hours and per-device trends | `python/analytics` |
| 8 | One-command environment (`docker compose up`) and a scripted demo scenario | `docker-compose.yml`, `scripts/` |

Items 1–7 are the system as originally specified. Item 8 exists because the most expensive
failure in a project demo is an environment that does not start on the evaluator's machine,
or a live demo that depends on a random anomaly firing on cue — see
[Build and run](#build-and-run).

### Layer 2 — additions, by unit

| Unit | Addition | What it demonstrates |
| ---- | -------- | -------------------- |
| I | **Swappable accept strategy** — thread-per-client and fixed thread pool behind one interface, plus a load-test harness | Turns the concurrency-model argument in `DESIGN.md` into a measured comparison |
| I | **Graceful shutdown** — stop accepting, drain in-flight handlers, close connections | Thread lifecycle and coordinated termination |
| I | **Race-condition demonstration** — an unsynchronised counter losing updates, then the corrected version | Why the synchronisation in the dispatcher is there |
| II | **Custom-painted live strip chart** drawn in `paintComponent` with `Graphics2D` | Owner-drawn Swing components, not a charting library |
| II | **`SwingWorker` responsiveness demonstration** — the same query on and off the EDT | The single-threaded event dispatch model |
| II | **Live threshold editor** — edit a limit in the UI, write through JDBC, engine reloads, alert fires | One action exercising Units I, II, and III together |
| II | **DFA state panel** — highlights the current automaton state as characters arrive | Makes the Unit V automaton visible while it runs |
| III | **Batched, pooled inserts** measured against naive autocommit | Round-trip cost and why batching matters |
| III | **Transaction with rollback** — reading and event written atomically, failure forced mid-transaction | ACID behaviour over JDBC |
| III | **SQL-injection demonstration** — a concatenated query exploited, then the parameterised fix | Why every DAO uses `PreparedStatement` |
| III | **`EXPLAIN` comparison** with and without the `(device_id, reading_ts)` index | Index selection and query planning |
| IV | **Serial vs. `Pool` timing** across the per-device analyses | The actual speedup from process-level parallelism |
| IV | **Time-of-use cost model** — tariff bands, monthly bill, and load-shifting savings | Turns raw telemetry into an actionable recommendation |
| V | **Error-locating rejection** — reports the column and the expected character set on failure | The trap state as a diagnostic, not just a reject |
| V | **Randomised equivalence check** — ~100k generated strings, DFA accept vs. reference regex match | Empirical evidence the automaton recognises the intended language |

---

## Wire protocol and DFA validation

### Message format

Meters and server speak a line-oriented text protocol. One reading is one line, terminated
by a newline (`\n`). A line has a fixed `RDG` header followed by five tagged, pipe-delimited
fields, in a fixed order:

```
RDG|D<deviceId>|T<epochMillis>|V<voltage>|I<current>|P<power>\n
```

| Token | Tag | Type | Meaning |
| ----- | --- | ---- | ------- |
| `RDG` | — | literal header | marks the start of a reading frame |
| `D`   | `D` | unsigned integer | device id (foreign key to `devices`) |
| `T`   | `T` | unsigned integer | meter-side timestamp, epoch milliseconds |
| `V`   | `V` | decimal (`digits.digits`) | RMS voltage, volts |
| `I`   | `I` | decimal (`digits.digits`) | RMS current, amperes |
| `P`   | `P` | decimal (`digits.digits`) | real power, watts |

Example of a well-formed line:

```
RDG|D3|T1721817600000|V228.40|I4.10|P998.20
```

The single source of truth for the delimiter, tags, header, and terminator is
`protocol.MeterMessage`, referenced by both the simulator (which formats) and the
validator/parser (which read), so producer and consumer cannot drift apart. It also defines
the dashboard live feed's subscribe handshake, which keeps every string this system puts on
a wire declared in one file. The decimal fields are formatted under `Locale.ROOT`: the
default would render `228,40` under a locale such as `fr-FR`, and every meter would then be
rejected on that machine and nowhere else.

### The alert frame

The dashboard's live feed carries one more frame type, added in Phase 3: the alerts the rule
engine raises. A reading frame cannot express one, which is the price `DESIGN.md` predicted
for reusing the meter format on the feed. It is built from the same pieces — the header, the
delimiter, one tag per field, the newline terminator — so it is read by the same kind of
code:

```
ALT|D<deviceId>|T<epochMillis>|E<eventType>|S<severity>|M<measured>|L<limit>|X<detail>\n
```

```
ALT|D2|T1721817600000|EVOLTAGE_SPIKE|SCRITICAL|M264.00|L253.00|XLiving Room HVAC: supply at 264.00 V, above the 253.00 V ceiling
```

Three details are deliberate. The **detail is last** and is free text, so it may contain
spaces; the delimiter and the terminator are stripped from it on the way out, because a rule
description containing a `|` would otherwise produce a frame that splits into the wrong
number of fields. The **`triggering_reading_id` is not on the wire** — it is a database key,
and a dashboard running without a database could do nothing with it but be misled, so
everything the alert log displays is carried on the frame itself. And alert frames are **not
run through the DFA**: the automaton recognises the meter grammar, which is the language the
untrusted side of the system speaks. Alerts are checked instead by `MessageParser.parseAlert`,
which validates every field rather than assuming the sender got it right.

Alerts travel on the same connection as the readings, and therefore in order behind the
reading that caused them: two channels would let the alert about a 264 V reading arrive
before the 264 V itself, and a tile would go red for a value it was not showing.

### The validating DFA

Because the format is a regular language, it is validated by a **deterministic finite
automaton** (`protocol.WireFormatValidator`) before any field is parsed. The automaton
consumes the line one character at a time; it accepts only if the entire line matches the
grammar, and it drops to a non-accepting dead (trap) state on the first character that does
not fit. Validation is therefore single-pass, O(n) in the line length, with O(1) memory —
and malformed input never reaches the numeric parsing code.

Input tokens used below: `digit` is any of `0`–`9`; `PIPE` is `|`; `DOT` is `.`; `LF` is
the newline `\n`; letters stand for themselves. Any `(state, input)` pair not listed
transitions to the dead state.

```mermaid
stateDiagram-v2
    [*] --> S0
    S0 --> S1: R
    S1 --> S2: D
    S2 --> S3: G
    S3 --> S4: PIPE
    S4 --> S5: D
    S5 --> S6: digit
    S6 --> S6: digit
    S6 --> S7: PIPE
    S7 --> S8: T
    S8 --> S9: digit
    S9 --> S9: digit
    S9 --> S10: PIPE
    S10 --> S11: V
    S11 --> S12: digit
    S12 --> S12: digit
    S12 --> S13: DOT
    S13 --> S14: digit
    S14 --> S14: digit
    S14 --> S15: PIPE
    S15 --> S16: I
    S16 --> S17: digit
    S17 --> S17: digit
    S17 --> S18: DOT
    S18 --> S19: digit
    S19 --> S19: digit
    S19 --> S20: PIPE
    S20 --> S21: P
    S21 --> S22: digit
    S22 --> S22: digit
    S22 --> S23: DOT
    S23 --> S24: digit
    S24 --> S24: digit
    S24 --> S25: LF
    S25 --> [*]
```

Transition table (dead-state transitions omitted; `S25` is the sole accepting state):

| State | On input | Next state | Reading |
| ----- | -------- | ---------- | ------- |
| S0 | `R` | S1 | start of header |
| S1 | `D` | S2 | header |
| S2 | `G` | S3 | header complete |
| S3 | `PIPE` | S4 | field separator |
| S4 | `D` | S5 | device tag |
| S5 | `digit` | S6 | first device-id digit |
| S6 | `digit` | S6 | more device-id digits |
| S6 | `PIPE` | S7 | end of device id |
| S7 | `T` | S8 | timestamp tag |
| S8 | `digit` | S9 | first timestamp digit |
| S9 | `digit` | S9 | more timestamp digits |
| S9 | `PIPE` | S10 | end of timestamp |
| S10 | `V` | S11 | voltage tag |
| S11 | `digit` | S12 | voltage integer part |
| S12 | `digit` | S12 | more integer digits |
| S12 | `DOT` | S13 | decimal point |
| S13 | `digit` | S14 | voltage fraction (>=1 digit required) |
| S14 | `digit` | S14 | more fraction digits |
| S14 | `PIPE` | S15 | end of voltage |
| S15 | `I` | S16 | current tag |
| S16 | `digit` | S17 | current integer part |
| S17 | `digit` | S17 | more integer digits |
| S17 | `DOT` | S18 | decimal point |
| S18 | `digit` | S19 | current fraction |
| S19 | `digit` | S19 | more fraction digits |
| S19 | `PIPE` | S20 | end of current |
| S20 | `P` | S21 | power tag |
| S21 | `digit` | S22 | power integer part |
| S22 | `digit` | S22 | more integer digits |
| S22 | `DOT` | S23 | decimal point |
| S23 | `digit` | S24 | power fraction |
| S24 | `digit` | S24 | more fraction digits |
| S24 | `LF` | S25 | **accept** |

Only a line that drives the DFA into `S25` is handed to `MessageParser`; everything else is
rejected at the door. Splitting responsibilities this way — the DFA decides *whether* a line
is legal, the parser only *extracts* fields from a line already known to be legal — keeps
each side small and independently testable.

### Error-locating rejection

A plain accept/reject verdict is enough to protect the parser but useless for diagnosing a
misbehaving meter. Because the automaton already knows the index at which it entered the trap
state and which transitions were defined for the state it left, rejection returns a
`ValidationResult` carrying the failure position and the set of characters that would have
been legal there:

```
RDG|D3|T1721817600000|V228.4x|I4.10|P998.20
                            ^
  col 28: in state S14, expected digit or '|', got 'x'
```

This costs one integer of extra state and no additional passes over the input — the
diagnostic falls out of the automaton's structure rather than being bolted on.

### Verifying the automaton

The wire format is small enough to write a reference regular expression for, which gives a
cheap and convincing correctness argument: a randomised test generates roughly 100,000
strings — well-formed lines, lines with one mutated character, truncated lines, and entirely
random noise — and asserts that

```
validator.accepts(s) == referencePattern.matcher(s).matches()
```

for every one. Agreement across that corpus is strong empirical evidence that the
hand-constructed transition table recognises exactly the intended language. Targeted
`WireFormatValidatorTest` cases cover the boundaries the fuzzer is unlikely to hit by chance:
empty input, header-only input, a missing fractional digit, a missing terminator, and
trailing characters after the newline.

---

## Database schema

MySQL 8.x. Four tables: static reference data (`devices`, `thresholds`) and the growing
time series (`readings`, `events`). Full DDL is in [`sql/schema.sql`](sql/schema.sql); seed
data is in [`sql/seed.sql`](sql/seed.sql).

### `devices` — the appliance catalogue

One row per monitored appliance/meter; changes rarely.

| Column | Type | Purpose |
| ------ | ---- | ------- |
| `device_id` | `INT` PK, auto-increment | surrogate key referenced everywhere |
| `name` | `VARCHAR(64)`, unique | human label, e.g. "Kitchen Refrigerator" |
| `appliance_type` | `VARCHAR(32)` | category used for grouping/analytics |
| `location` | `VARCHAR(64)` | room or circuit |
| `rated_voltage` | `DECIMAL(6,2)` | nominal operating voltage (V) |
| `rated_power_watts` | `DECIMAL(10,2)` | manufacturer power rating (W); baseline for overload limits |
| `created_at` | `TIMESTAMP` | row creation time |

### `readings` — the time series

The high-volume table; one row per meter reading.

| Column | Type | Purpose |
| ------ | ---- | ------- |
| `reading_id` | `BIGINT` PK, auto-increment | surrogate key |
| `device_id` | `INT` FK → `devices` | which appliance produced the reading |
| `reading_ts` | `DATETIME(3)` | measurement time reported by the meter (millisecond precision) |
| `voltage` | `DECIMAL(6,2)` | RMS volts |
| `current_amp` | `DECIMAL(6,2)` | RMS amperes |
| `power_watts` | `DECIMAL(10,2)` | real power (W) |
| `received_at` | `TIMESTAMP(3)` | server ingest time; differs from `reading_ts` by network/queue latency |

Indexed on `(device_id, reading_ts)` to back the dashboard's "this device over this window"
history queries and the analytics scans. The effect of that index on the query plan is
measured in [Engineering evidence](#engineering-evidence).

### `thresholds` — detection limits

The limits the rule engine evaluates against. A `NULL` `device_id` is a **global default**
applied to any device without a specific override.

| Column | Type | Purpose |
| ------ | ---- | ------- |
| `threshold_id` | `INT` PK, auto-increment | surrogate key |
| `device_id` | `INT` FK → `devices`, nullable | target device, or `NULL` for the global default |
| `metric` | `ENUM('VOLTAGE','CURRENT','POWER')` | which quantity this row bounds |
| `min_value` | `DECIMAL(10,2)`, nullable | lower bound; a reading below it is a sag/under condition |
| `max_value` | `DECIMAL(10,2)`, nullable | upper bound; a reading above it is a spike/overload |
| `description` | `VARCHAR(128)`, nullable | human note |

A unique key on `(device_id, metric)` enforces one row per device-and-metric (with the
`NULL` device row acting as the default for that metric). This is the table the dashboard's
[threshold editor](#dashboard) writes to.

### `events` — power-quality alerts

One row per alert raised by the rule engine.

| Column | Type | Purpose |
| ------ | ---- | ------- |
| `event_id` | `BIGINT` PK, auto-increment | surrogate key |
| `device_id` | `INT` FK → `devices` | the offending appliance |
| `triggering_reading_id` | `BIGINT` FK → `readings`, nullable | the reading that tripped the rule (`SET NULL` on delete) |
| `event_type` | `ENUM('VOLTAGE_SPIKE','VOLTAGE_SAG','LOAD_OVERLOAD')` | what was detected |
| `severity` | `ENUM('INFO','WARNING','CRITICAL')` | how far past the limit |
| `measured_value` | `DECIMAL(10,2)` | the value observed |
| `threshold_value` | `DECIMAL(10,2)` | the limit that was crossed |
| `detail` | `VARCHAR(255)`, nullable | human-readable description |
| `detected_at` | `TIMESTAMP(3)` | server-side detection time |

Indexed on `(device_id, detected_at)` and on `detected_at` to serve per-device alert
history and the "most recent alerts" view.

A reading and the event it triggers are written in a **single transaction**, so the database
can never hold an alert that points at a reading which was never committed — demonstrated
explicitly in [Failure-mode demonstrations](#failure-mode-demonstrations).

---

## Rule engine detection logic

The rule engine (`rules.RuleEngine`) holds a list of independent `DetectionRule` strategies
and applies every rule to every reading the dispatcher gives it. It reads the thresholds
once at start-up into an in-memory `RuleContext` (device-specific rows override the global
default), so the hot evaluation path does no database round-trips.

Three rules ship:

- **`VoltageSagRule`** — fires when `reading.voltage < min_value` of the device's `VOLTAGE`
  threshold. Produces a `VOLTAGE_SAG` event.
- **`VoltageSpikeRule`** — fires when `reading.voltage > max_value` of the device's
  `VOLTAGE` threshold. Produces a `VOLTAGE_SPIKE` event.
- **`LoadOverloadRule`** — fires when `reading.power_watts > max_value` of the device's
  `POWER` threshold (typically the rated wattage plus a start-up tolerance). Produces a
  `LOAD_OVERLOAD` event.

With the seeded defaults, the supply band is 207–253 V (±10% around a 230 V nominal), so a
reading of 262 V raises a `VOLTAGE_SPIKE` and 198 V raises a `VOLTAGE_SAG`; a refrigerator
(overload ceiling 500 W) drawing 540 W raises a `LOAD_OVERLOAD`. A threshold row bounds one
device and one metric; a row with a `NULL` device id is the default for any device without
an override. A metric with **no** row at all is unbounded and raises nothing — a missing
threshold means nobody has said what is normal here, and inventing a limit would raise alerts
against numbers no one configured.

### Severity

Each rule sets the event's **severity** from the size of the excursion, as a fraction of the
limit rather than an absolute margin: one rule bounds a router at 40 W and a water heater at
3300 W, and "50 W over" is a fault on the first and noise on the second, while "10% over"
means the same thing on both.

The two cut-offs are each rule's own, because voltage and power are not comparable on that
scale — a supply drifting 5% out of band is a serious event, while a motor drawing 5% over
its rating on start-up is an appliance working normally:

| Rule | `WARNING` at | `CRITICAL` at | Worked example |
| ---- | ------------ | ------------- | -------------- |
| `VoltageSpikeRule` | 1% past the ceiling | 4% past | 256 V → `WARNING`; 264 V → `CRITICAL` |
| `VoltageSagRule` | 1% below the floor | 4% below | 205 V → `WARNING`; 198 V → `CRITICAL` |
| `LoadOverloadRule` | 2% past the ceiling | 20% past | 540 W vs 500 W → `WARNING`; 625 W → `CRITICAL` |

The seeded power ceilings already include a start-up allowance, which is why the overload
bands are the wider pair.

### Where the events go

The engine **evaluates but does not persist**: it is a pure function from a reading to the
alerts it deserves, which is what makes it testable without a database. Writing is
`PersistenceSink`'s job, because an event's `triggering_reading_id` is the key of a row being
inserted in the same transaction — so whoever owns that transaction has to own the event
insert too, or the two cannot be atomic. The sink inserts the reading, calls the engine with
the key it got back, inserts the events, commits, and only then publishes the alerts to the
dashboards. Publishing last is deliberate: an alert on screen that a rolled-back transaction
means never happened is one the operator cannot go back and find in the log.

New rule types are added by writing another `DetectionRule` implementation and registering
it; the engine, the sink, and the ingest path are untouched.

The `RuleContext` is **reloadable**: it is `volatile` and replaced wholesale, never mutated,
so a worker mid-evaluation sees one consistent set of thresholds and the next reading picks
up the edit. That is what the threshold editor commits into, over the live feed's `RELOAD`
command — the dashboard writes to the `thresholds` table and then tells the server to rebuild
its context, because the server has no other way of noticing. Detection itself runs on
the dispatcher's worker rather than on the `ClientHandler` read loop, so a burst of anomalies
cannot slow meter ingestion. The reasoning is expanded in [`docs/DESIGN.md`](docs/DESIGN.md).

Detection comes up **with persistence and not without it**: the thresholds live in the
database, so `--no-persistence` starts a server that validates, dispatches, and broadcasts
readings but evaluates nothing.

---

## Dashboard

The Swing client is structured as Model–View–Controller: `LiveFeedClient` receives the
published stream, the `DashboardController` applies it to an observable `DashboardModel`, and
the views repaint from the model. All socket reads and JDBC queries run off the event
dispatch thread via `SwingWorker`; only model-to-view updates touch the EDT.

| Panel | Shows | Notes | Status |
| ----- | ----- | ----- | ------ |
| `AppliancePanel` | One tile per appliance: live voltage, current, power, and a sparkline of recent load | Drawn entirely in `paintComponent` with `Graphics2D`; colour reflects the most severe active condition | built |
| `HistoryChartPanel` | Per-device history over a selectable window, read over JDBC | Query runs on a `SwingWorker`, never on the EDT | built |
| `EventLogPanel` | Running alert log, newest first, severity-coloured | Backfilled from `events` at start-up, then fed live by the publisher's alert channel | built |
| `LiveChartPanel` | A scrolling strip chart of the last 60 seconds of load across the home | Drawn directly in `paintComponent` with `Graphics2D` — no charting library. Sampled on a one-second timer rather than on arrival, so the x-axis is a clock and not a record of when six meters happened to report; a stale appliance drops out of the total | built |
| `ThresholdEditorPanel` | Editable per-device limits, committed through `ThresholdDao` | Writes to MySQL, then sends `RELOAD` up the live feed so the server rebuilds its `RuleContext`. One row commits at a time, and a blank bound is `NULL` rather than zero | built |
| `DfaStatePanel` | The automaton's current state as characters stream in | Steps the real `WireFormatValidator` one character at a time; highlights the active row of the transition table and flashes the trap state on rejection. The frame is editable, and "Corrupt one character" supplies the rejection the live feed never can | built |

The live feed carries the same `RDG|…` frames the meters send, so the dashboard decodes it
with the same `WireFormatValidator` and `MessageParser` the server uses on the way in — one
grammar, one parser, tested once. The subscribe handshake is defined alongside the frame
format in `protocol.MeterMessage`, as is the [`ALT|…` alert frame](#the-alert-frame) the same
connection carries.

An arriving alert does two things: it goes to the top of the event log, and it colours the
tile of the appliance it names. The colour is **held for three seconds** rather than cleared
by the next reading — an alert arrives immediately behind the reading that caused it, so
clearing on the next one would make a sustained fault flash green once a second, and a tile
that spends half a fault claiming the appliance is fine is worse than no tile. A tile
therefore stays coloured for as long as the condition lasts and returns to normal a moment
after it stops. Where one reading raises two alerts, the more severe one is the colour.

`DashboardModel` refuses to be mutated from anywhere but the event dispatch thread, and
throws naming the offending thread if it is. Swing components are not thread-safe, and the
symptom of getting that wrong is not an exception but a repaint that goes missing once an
hour on someone else's machine.

The threshold editor is the shortest path through the whole system in a single user action:
a UI edit (Unit II) becomes a JDBC write (Unit III) that reloads the rule engine (Unit I) and
changes which meter readings raise alerts (Unit V validates those readings on the way in).

---

## Python analytics module

`python/analytics` is a standalone, read-only companion that connects to the same MySQL
database and mines the accumulated history for insights the live dashboard does not compute.
It is run offline (for the report and demos), independent of the Java processes.

- `config.py` — loads MySQL connection parameters from the environment.
- `db.py` — opens connections and runs the read-only history queries. Every one is a
  `SELECT`, and every parameter is bound rather than formatted into the SQL — the same rule
  the Java DAOs follow with `PreparedStatement`, for the same reason.
- `peak_hours.py` — buckets all readings by hour-of-day (00–23) and ranks the hours at which
  the whole home draws the most power.
- `device_trends.py` — analyses one device's history (average, min/max, duty cycle, direction
  of trend). This is the unit of work that is parallelised.
- `cost_model.py` — applies a time-of-use tariff to the measured consumption: the monthly
  bill per device, and the saving available from shifting a deferrable load (water heater,
  washing machine) out of the peak band.
- `benchmark.py` — runs the per-device analyses serially and then through the pool, and
  reports the wall-clock times and the resulting speedup.
- `runner.py` — the orchestrator: reads the device list, maps `device_trends.analyze_device`
  across a `multiprocessing.Pool` (**one worker process per device**, so the per-device
  scans overlap instead of running serially), then runs the peak-hour and cost analyses, and
  renders all of them as plain-text tables.
- `__main__.py` — the `python -m analytics` entry point, guarded by
  `if __name__ == "__main__"` as `multiprocessing` requires.

The `multiprocessing` step is the syllabus focus of Unit IV: the workload partitions cleanly
by device, so it is a natural fit for a process pool. `benchmark.py` exists so the choice can
be defended with a measured speedup rather than an assertion — the number lands in
[Evidence 4](#4-python-parallel-speedup). It also checks that the pooled run produced the same
summaries as the serial one, because a speedup is only interesting if the fast answer is the
same answer.

Each module splits its fetching from its arithmetic — `analyze_device` opens the connection,
`summarise` does the sums — so every analysis function is a pure function over rows. That is
what makes the Python suite runnable with neither MySQL nor the driver installed, and it is
why the worker is `functools.partial(analyze_device, since=...)` rather than a closure: the
callable is pickled and sent to each process, and only something importable by name survives
that.

**Averaging, and why it is done per device first.** The obvious aggregation — add every
`power_watts` in an hour and divide by the row count — answers a question nobody asked: it
gives the average draw of a *typical appliance*, and a meter that reports twice as often as
its neighbours pulls the answer towards itself. What the home draws at 19:00 is the sum over
appliances of what each is drawing, so each device is averaged over its own samples first and
the averages are added. The result is a load in watts that is comparable between hours however
the sampling varied, and it converts straight into kWh for the cost model.

The cost model turns the telemetry into something actionable: rather than reporting only that
the water heater averages 1.8 kW between 18:00 and 21:00, it reports what those hours cost
under the peak tariff and what the same consumption would cost shifted into the off-peak
band. Tariff bands are configuration, not code, so they can be matched to a local supplier;
the default is a plausible domestic schedule of off-peak 22:00–06:00, standard 06:00–18:00,
and peak 18:00–22:00. A tariff that fails to price all 24 hours is rejected rather than
silently costing part of the day at zero. Only **deferrable** loads (washer, heater,
dishwasher) are recommended for shifting: a refrigerator's peak-band energy is expensive and
cannot be rescheduled, and a report that suggests running it at 02:00 is one that gets
ignored wholesale.

---

## Engineering evidence

The rubric grades to the Analyze/Evaluate level, so each significant design choice is backed
by a measurement taken on this system rather than by assertion. These are the four
experiments. Every number below was produced by the command shown above its table; each
harness also writes a CSV, so these tables are transcribed from a run rather than retyped
from a terminal. The unedited output of all four runs is kept in
[`docs/evidence/`](docs/evidence/), so these tables can be checked rather than trusted.

Two of the four did not come out the way this document predicted. The predictions have been
left in place next to the results — see
[Where the measurements contradicted the design](#where-the-measurements-contradicted-the-design).

### Measurement conditions

| | |
| - | - |
| CPU | 13th Gen Intel Core i5-13500H, 16 logical cores |
| Memory | 15 GiB |
| Storage | NVMe SSD |
| OS | Arch Linux, kernel 7.1.2 |
| JDK | OpenJDK 26.0.1 (the build targets 17) |
| MySQL | 8.4.11, in the project's own Docker Compose container |
| Python | 3.14.6 |
| Stored history at measurement time | ~113,000 readings across 6 devices, ~950 events |

Everything ran on one host, including the database. Nothing here measures behaviour across a
real network — which is the one thing that would actually test the TCP-versus-UDP argument in
[`docs/DESIGN.md`](docs/DESIGN.md).

### 1. Concurrency model — thread-per-client vs. thread pool

`DESIGN.md` argues that thread-per-client is correct *at this project's scale*. The harness
(`bench.IngestBenchmark`) drives N synthetic meters at a fixed rate — 10 readings/s each —
through each accept strategy and records sustained throughput, latency percentiles, and peak
thread count. It stands up the real `ClientHandler` and the real `ReadingDispatcher` and
terminates them in a counting sink, so every frame goes through the real DFA and parser
without the database's latency confounding the comparison.

```bash
mvn exec:java -Dexec.mainClass=com.smarthome.energy.bench.IngestBenchmark -Dexec.args="--meters 10,50,200 --duration 60s"
```

| Meters | Strategy | Throughput (readings/s) | p50 latency (ms) | p99 latency (ms) | Peak threads |
| ------ | -------- | ----------------------- | ---------------- | ---------------- | ------------ |
| 10 | thread-per-client | 100 | 0 | 2 | 10 |
| 10 | pool (8) | 80 | 0 | 1 | 8 |
| 50 | thread-per-client | 500 | 0 | 1 | 50 |
| 50 | pool (8) | 80 | 0 | 1 | 8 |
| 200 | thread-per-client | 2,000 | 0 | 1 | 200 |
| 200 | pool (8) | 80 | 0 | 1 | 8 |

> **Predicted before running it:** *"indistinguishable at 10 meters, with the pool pulling
> ahead as the connection count grows. That is precisely the argument — the simpler model is
> chosen because the measurement shows it costs nothing here, not because pools are bad."*
>
> **Both halves of that are wrong.** The pool is behind at ten meters, not level with it, and
> it falls further behind at every step rather than pulling ahead.

Thread-per-client delivered every reading offered to it at all three sizes, including 2,000
readings/s across 200 connections. The pool of eight delivered 80 readings/s at *every* size,
which is exactly eight meters' worth — because eight is how many meters it was reading from:

```
   10 meters thread-per-client  offered   100/s  delivered   100/s  served  10/10
   10 meters pool(8)            offered   100/s  delivered    80/s  served   8/10
   50 meters thread-per-client  offered   500/s  delivered   500/s  served  50/50
   50 meters pool(8)            offered   500/s  delivered    80/s  served   8/50
  200 meters thread-per-client  offered 2,000/s  delivered 2,000/s  served 200/200
  200 meters pool(8)            offered 2,000/s  delivered    80/s  served   8/200
```

A pool multiplexes many *short* tasks across few threads, because each task releases its
thread promptly. A `ClientHandler` is the opposite: it blocks on a socket read for the whole
life of the connection. Submitting it to a pool of eight therefore does not share eight
threads among 200 meters — it means meters 9 through 200 sit in the queue, TCP-connected and
never read from, until one of the first eight disconnects. **The pool bounds the thread count
by bounding how many meters are served at all.**

**The latency columns are a trap, and are printed anyway.** The pooled runs' p50 and p99 are
as good as thread-per-client's or better. That is not the pool performing well — the readings
that would have been late were never delivered, so they are absent from the distribution
instead of in its tail. The honest column is "meters actually read", which is why the harness
reports it. A benchmark that printed only the first six columns of the table above would have
made the pool look competitive.

The conclusion is narrower and more useful than the one this project started with: not
"thread-per-client is acceptable at our scale", but **a pool is the wrong tool wherever a task
holds its thread for the lifetime of a connection, at any scale**. The pool this system does
have — the dispatcher's workers — is in the right place, because "deliver one reading" is a
task that returns. Serving 200 connections would need non-blocking I/O, not a smaller number
of blocking threads.

*Caveat:* the load generators and the server share one JVM and the same cores, so the
absolute throughput is capped by the harness. Both strategies pay that cost equally, so the
comparison stands; the figures are "under this harness", not a capacity claim. Latency is
measured to the millisecond because that is the resolution of the meter-side timestamp the
wire format carries, so `p50 = 0` means "below what the protocol records" — the mean, which
the harness also prints, ranged from 0.15 ms to 0.54 ms.

### 2. JDBC insert strategy

`bench.JdbcBatchBenchmark` inserts 50,000 readings three ways. Each strategy differs from the
one above it in exactly one respect, so the table reads as two isolated effects rather than
one confounded comparison.

```bash
mvn exec:java -Dexec.mainClass=com.smarthome.energy.bench.JdbcBatchBenchmark -Dexec.args="--rows 50000"
```

| Strategy | Rows | Wall clock (s) | Rows/s |
| -------- | ---- | -------------- | ------ |
| Autocommit, one `INSERT` per row | 50,000 | 248.78 | 201 |
| `PreparedStatement`, batched (500) | 50,000 | 5.62 | 8,901 |
| Batched + pooled connection | 50,000 | 4.83 | 10,351 |

The baseline is not a straw man built to lose: it is exactly what `ReadingDao.insert(Reading)`
does on every call and what `PersistenceSink` does for every reading that arrives — a fresh
connection and an implicit transaction per row. At 201 rows/s against the six readings a
second the fleet actually offers, it has about thirty times the headroom it needs, which is
why the simple version is still in the system.

The two deltas are worth more than the totals:

- **Batching is worth 44×**, and almost all of it is the per-row commit — 50,000 durable
  transactions against 100.
- **Pooling is worth a further 16%**, which is a TCP handshake and a MySQL authentication
  exchange amortised away per unit of work.

That ordering says where the effort would go if this ever needed more throughput, and it says
that a connection pool — the change people reach for first — is the smaller half of it.

Every row the benchmark writes is stamped inside a sentinel window in the year 2000 and
deleted afterwards, so 150,000 benchmark rows never distort the analytics or the index
comparison.

### 3. Index effectiveness

The dashboard's history query — one device, the last 15 minutes, in time order — planned with
and without the composite index, over ~113,000 stored readings:

```bash
docker compose exec -T mysql mysql -u energy_app -pchange_me smart_home_energy < sql/explain_index.sql
```

| Index present | `type` | `rows` examined | `Extra` |
| ------------- | ------ | --------------- | ------- |
| Yes — `(device_id, reading_ts)` | `range` | 899 | `Using index condition` |
| No — `device_id` alone | `ref` | 36,240 | `Using where; Using filesort` |

Forty times fewer rows examined, and no sort: with the composite index the range on
`reading_ts` is served by the index itself, which also returns the rows already in
`ORDER BY` order. Two details had to be got right for this to mean anything, and both are
recorded in the script:

**The index cannot simply be dropped.** `fk_readings_device` requires an index on
`device_id`, and `idx_readings_device_ts` is currently the index satisfying that requirement,
so `DROP INDEX` fails with error 1553. The script creates a single-column index on
`device_id` first and removes it at the end. That makes the comparison the honest one anyway:
"without the index" cannot mean "with no index on `device_id`", because the schema would
never permit that. What is being measured is exactly what the *second column* buys.

**The window matters, and an all-time window measures nothing.** Planned over
`BETWEEN '2000-01-01' AND '2100-01-01'`, both plans come out as full table scans — selecting
every row a device has genuinely is cheaper to scan than to seek 18,000 index entries and
fetch each row. That is the optimiser being right, not the index being useless. The first
version of this measurement used exactly that range and showed nothing; the query was
corrected to the 15-minute window the dashboard actually defaults to.

### 4. Python parallel speedup

```bash
cd python && python -m analytics.benchmark
```

| Mode | Workers | Wall clock (s) | Speedup |
| ---- | ------- | -------------- | ------- |
| Serial | 1 | 1.47 | 1.00× |
| `multiprocessing.Pool` | 2 | 1.08 | 1.36× |
| `multiprocessing.Pool` | 4 | 0.79 | 1.85× |
| `multiprocessing.Pool` | 8 | 0.69 | 2.12× |

Real, and clearly flattening — which was predicted, for a reason that was only half right.
The prediction was that the per-device analysis is IO-bound on the database, so the curve
flattens once workers outnumber the connections MySQL will usefully serve in parallel. That
is one of three caps, and probably not the binding one here:

1. **There are only six devices.** Eight workers cannot beat six units of work, so the 4→8
   step is measuring almost nothing but pool construction. `--repeat N` exists to give the
   pool more work units than there are devices, for anyone who wants the curve without this
   cap.
2. **The work is IO-bound on MySQL**, so parallelising it overlaps waiting rather than
   computation.
3. **Pool construction is not free**, and on Python 3.14 it is less free than it was: the
   default start method on Linux is now `forkserver` rather than `fork`, so every worker
   re-imports the module. The benchmark times the whole operation, pool setup included,
   because that is what the report pays on every run — timing `pool.map` alone would flatter
   the parallel rows by hiding a cost the caller cannot avoid.

**What this does and does not establish about the GIL.** The choice of `multiprocessing` over
`threading` is usually justified by the GIL, and that argument holds — but this measurement
is not what demonstrates it. An IO-bound workload releases the GIL while it waits, so threads
would have parallelised this too. What the measurement establishes is that the parallelism is
real and that its ceiling is the database, which is the honest finding rather than the
flattering one.

The benchmark also checks that the pooled run produced the same summaries as the serial one
(`results agree with the serial baseline (6 summaries)`). A speedup is only interesting if
the fast answer is the same answer, and those results cross a process boundary as a pickle on
the way back.

### Where the measurements contradicted the design

Two of the four experiments refuted something this project believed. Both predictions are
still in the sections above, next to the results, because a document that quietly edits its
predictions to match its outcomes is not evidence of anything.

| Belief | What the measurement showed | What changed |
| ------ | --------------------------- | ------------ |
| A thread pool would match thread-per-client at 10 meters and pull ahead as connections grow | The pool was behind at every size, and served only as many meters as it had threads | The argument for thread-per-client stopped being "we are small enough to get away with it" and became "a pool cannot help when the task holds its thread for the connection's lifetime". `AcceptStrategy`'s Javadoc and `docs/REPORT.md` §5.1 carry the corrected reasoning |
| The Python speedup would be capped by database concurrency | It is, but the binding cap at this size is having only six devices to divide among eight workers | `--repeat` was added so the curve can be measured past that cap, and the three caps are now named separately rather than attributed to one cause |

A third correction came out of building the evidence rather than running it: the scripted
demo's threshold edit originally raised the refrigerator's ceiling to 520 W while the
appliance drew 540, and the step cueing that edit would have dropped the load back to normal
at the same moment. Either would have produced a demonstration that appeared to work and
proved nothing. Both are now asserted by `ScenarioTest` — see
[The scripted demo](#the-scripted-demo).

---

## Failure-mode demonstrations

Four short, deliberately-broken paths, each paired with its corrected counterpart. They exist
because demonstrating a failure is stronger evidence of understanding than asserting the fix
was necessary, and each is a direct hit on a syllabus teaching point.

| Demonstration | Broken behaviour | Corrected behaviour | Unit |
| ------------- | ---------------- | ------------------- | ---- |
| **Lost update** | Dispatcher counters incremented without synchronisation under concurrent handlers; totals come out short | Atomic counters; totals exact | I |
| **Frozen UI** | The query run directly on the event dispatch thread; the window stops repainting until it returns | The same query on a `SwingWorker`; the UI stays live | II |
| **SQL injection** | A device-name lookup built by string concatenation, exploited against a throwaway table | The same lookup as a parameterised `PreparedStatement` | III |
| **Partial write** | Reading committed, event insert then fails — leaving an orphaned reading | Both in one transaction; the failure rolls back cleanly | III |

Each runs from the demo script (`scripts/demo.sh --failure <name>`, or
`FailureDemos --all`) and prints the before and after side by side. What they actually print:

| Demonstration | Broken | Corrected |
| ------------- | ------ | --------- |
| Lost update (8 threads × 200,000 increments; correct total 1,600,000) | **666,167** — 933,833 increments lost, 58% of them | **1,600,000** — exact |
| Frozen UI (a 3 s query, with a 50 ms timer ticking on the EDT) | longest gap between ticks **3,282 ms** — the window was dead for the whole query | longest gap **50 ms** — the timer's own interval |
| SQL injection (input `no such device' OR '1'='1`, against a 6-row table) | **6 rows** returned — the entire table | **0 rows** — the whole string is one name, and nothing is called that |
| Partial write (a reading, then an event that violates its foreign key) | **1 reading left behind** — an orphan whose alert was never recorded | **0** — the reading went back with the event |

Three notes on what these do and do not show.

**The lost-update figure is not stable, and that is the finding.** It is 58% on this machine
on this run; it will be a different fraction on yours. A defect that loses a different number
of updates every time is one no assertion catches, which is why the fix is structural rather
than a test.

**`SELECT SLEEP(3)` is not a query anybody would write.** The frozen-UI demonstration uses one
because the point is not what a query costs but which thread pays for it, and a deliberately
timed sleep makes the two runs comparable to the millisecond. The real `loadHistory` blocks
the same thread for the same reason, just for a duration that depends on how much history
happens to be stored.

**The injection demonstration stops short of the famous version.** `'; DROP TABLE …` does not
fire here, because Connector/J refuses multiple statements per `execute` unless
`allowMultiQueries` is set, and this project's URL does not set it. Saying so is more honest
than quietly demonstrating something weaker: the driver's default removes the spectacular
consequence and leaves the ordinary one, which is that the attacker, not the programmer,
decides what the `WHERE` clause means.

---

## Build and run

### Prerequisites

- JDK 17 or newer
- Apache Maven 3.9+
- Docker and Docker Compose (recommended), **or** a local MySQL 8.x
- Python 3.10+ (for the analytics module)

### Quick start

With Docker available, the database comes up already schema'd and seeded:

```bash
docker compose up -d          # MySQL 8, schema.sql and seed.sql applied on first boot
cp src/main/resources/db.properties.example src/main/resources/db.properties
mvn clean package
```

Then either start the three processes in three terminals, as under
[Manual setup](#manual-setup-no-docker) steps 4–6, or let the demo script do all of it:

```bash
./scripts/demo.sh                      # database, server, simulators, dashboard
./scripts/demo.sh --scenario incident  # the same, replaying the scripted three-minute fault
./scripts/demo.sh --failure lost-update   # one failure-mode demonstration, then exit
./scripts/demo.sh --list               # the scenarios and failure demonstrations
./scripts/demo.sh --stop               # stop whatever it started
```

The script builds first, waits for MySQL's healthcheck rather than guessing with a `sleep`,
waits for each port to accept before starting the process that connects to it, and records
every process it starts so Ctrl-C — or `--stop` from another terminal — takes the whole stack
down. It skips the Swing window when `DISPLAY` is unset instead of failing, so the same
command works over SSH.

`db.properties` is git-ignored so credentials never enter version control. The example file
already matches the Docker Compose credentials, so no editing is needed for the default
setup.

### Manual setup (no Docker)

Run the steps **in order** — the schema must exist before the server starts, and the server
must be running before the simulators or dashboard connect.

**1. Create the schema and seed data**

```bash
mysql -u root -p < sql/schema.sql
mysql -u root -p smart_home_energy < sql/seed.sql
```

Optionally create a dedicated application user:

```sql
CREATE USER 'energy_app'@'localhost' IDENTIFIED BY 'change_me';
GRANT SELECT, INSERT, UPDATE, DELETE ON smart_home_energy.* TO 'energy_app'@'localhost';
FLUSH PRIVILEGES;
```

**2. Configure database credentials**

```bash
cp src/main/resources/db.properties.example src/main/resources/db.properties
# edit db.properties with your JDBC URL, user, and password
```

**3. Build**

```bash
mvn clean package
```

**4. Start the server**

```bash
mvn exec:java -Dexec.mainClass=com.smarthome.energy.server.EnergyMonitorServer
```

(The server main is the configured default, so a bare `mvn exec:java` also starts it.)

| Option | Effect |
| ------ | ------ |
| `--meter-port N` | override the meter ingest port |
| `--dashboard-port N` | override the live-feed port |
| `--no-persistence` | run the live pipeline with no MySQL at all: readings are validated, dispatched, and broadcast to dashboards, but not stored. A diagnostic mode for working on the networking path — history, alerts, and the analytics have nothing to read without it |
| `--accept STRATEGY` | how an accepted connection gets a thread: `thread-per-client` (the default), `pool`, or `pool:N`. Read [Evidence 1](#1-concurrency-model--thread-per-client-vs-thread-pool) before choosing anything but the default — the pool serves only as many meters as it has threads |

The server prints a status line every ten seconds (`meters=6 threads=6/6 subscribers=1
accepted=… delivered=… dropped=… queued=… rate=…/s`) and drains the dispatcher on shutdown.
The `threads=` field is live/peak for the accept strategy in force.

**5. Start the meter simulators** — in a second terminal:

```bash
mvn exec:java -Dexec.mainClass=com.smarthome.energy.simulator.SimulatorLauncher
```

| Option | Effect |
| ------ | ------ |
| `--host H`, `--port N` | where the server is |
| `--interval MS` | milliseconds between readings from each meter |
| `--anomaly P` | probability that a reading is an injected spike, sag, or overload |
| `--corrupt P` | probability that a frame is deliberately damaged before being sent, to exercise the server's DFA rejection path |
| `--devices 1,2,3` | run a subset of the seeded fleet |
| `--seed N` | seed the generators, so an interesting run can be replayed exactly |

**6. Start the dashboard** — in a third terminal:

```bash
mvn exec:java -Dexec.mainClass=com.smarthome.energy.client.DashboardApp
```

| Option | Effect |
| ------ | ------ |
| `--host H`, `--port N` | where the server's live feed is |
| `--no-db` | skip JDBC entirely: live tiles only, no history or alerts |

The dashboard opens on the live feed alone if the database is unreachable, rather than
refusing to start — the live view is most useful precisely when something behind it has
broken. It reconnects on its own if the server restarts.

**7. Run the Python analytics** (any time after data has accumulated):

```bash
cd python
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python -m analytics
```

| Option | Effect |
| ------ | ------ |
| `--hours N` | only analyse the last N hours (default: all stored history) |
| `--top N` | how many peak hours to list (default: 5) |
| `--processes N` | worker processes to use (default: one per device) |

Connection settings come from `ENERGY_DB_HOST`, `ENERGY_DB_PORT`, `ENERGY_DB_USER`,
`ENERGY_DB_PASSWORD`, and `ENERGY_DB_NAME`, defaulting to the Docker Compose credentials —
so with the default setup nothing needs exporting. (The Java side deliberately refuses to
default its JDBC URL; this module may, because it only ever reads, so the cost of guessing
wrong here is a connection error rather than a write to the wrong database.)

### The scripted demo

A live demonstration should never depend on a random anomaly firing at the right moment, so
the simulators can replay a fixed scenario instead of generating purely random readings:

```bash
./scripts/demo.sh --scenario incident
```

The `incident` scenario runs for three minutes and is deterministic. `--list-scenarios` on
the simulator prints the same running order, and so does the launcher before it starts, so an
audience knows what to watch for:

| Time | What happens | What to point at |
| ---- | ------------ | ---------------- |
| 0:00–0:45 | All appliances nominal | Live tiles and the strip chart populating |
| 0:45 | Deliberate malformed line injected (device 3) | `DfaStatePanel` walks into the trap state; the server's rejection names the column |
| 1:00 | HVAC voltage held at 264 V | `VOLTAGE_SPIKE`, CRITICAL, in the event log |
| 1:20 | HVAC supply recovers | Its tile returns to green |
| 1:30 | Supply sags to 196 V across all devices | Simultaneous `VOLTAGE_SAG` events on every device |
| 1:50 | Supply recovers across the house | Every tile returns to green |
| 2:00 | Refrigerator draws 540 W against a 500 W ceiling | `LOAD_OVERLOAD`, WARNING, once per reading |
| 2:20 | **Cue:** raise device 1's `POWER` maximum to 560 in the Thresholds tab and commit | Engine reloads; the next reading no longer alerts |
| 2:40 | All values return to nominal | Event log stops growing; tiles return to green |

Two details of the timeline are load-bearing.

**The 2:20 step changes nothing about the load.** It is a cue for the operator, not a
scripted event — `Scenario.Kind.NOTE`, which the replay deliberately treats as neither
applying nor clearing anything. A step that quietly dropped the refrigerator back to normal
at the same moment would produce an identical screen whether the operator touched the editor
or not, and the one thing this part of the demonstration exists to prove is that the edit is
what stopped the alerts. The fridge is still drawing 540 W at 2:39.

**The new ceiling is 560 W, not 520 W.** An earlier draft of this table said 520, which is
below the 540 W the appliance is drawing — committing it would have reloaded the engine
faithfully and carried on alerting, in front of the audience. The scenario now aims the
overload and the edit either side of one number, and `ScenarioTest` asserts that every
scripted fault actually crosses the limit it is meant to trip, so the next such slip fails
the build instead of the demo.

### Tests

```bash
mvn test                      # unit tests, including the DFA suite
mvn test -Dtest=WireFormatFuzzTest   # ~100k randomised DFA vs. regex comparisons

cd python && python -m unittest discover -s tests -t .   # the analytics suite
```

157 Java tests and 33 Python tests at the end of Phase 4. The eight DAO round-trip tests skip
themselves with a stated reason when no database is reachable, so the build stays green on a
machine without Docker; everything else — the protocol, the dispatcher, the accept
strategies, the waveform generator, the scenario timeline, the rule engine, the dashboard
model and its strip chart, the benchmark's percentiles, and the analytics — runs anywhere,
headless included.

Two of those suites are worth a note, because "no database" would otherwise mean "not
tested":

- `PersistenceSinkTest` runs against `RecordingDatabase`, a JDBC driver in the test tree that
  records the calls made to it instead of storing anything. The claim under test — reading
  and events on one connection, in one transaction, alerts published only after the commit,
  everything rolled back if any insert fails — is a property of the *sequence of JDBC calls*,
  so it can be asserted exactly, on any machine. A test that needed MySQL would be skipped on
  every machine that lacks it, which is precisely where a regression would hide.
- The Python suite exercises the analysis functions, which take rows and return values, so it
  needs neither MySQL nor the MySQL driver installed.

Phase 4 adds four suites, three of which exist because their subject is a claim this document
makes:

- `AcceptStrategyTest` opens real sockets on a loopback port and counts how many of them get
  read from. That is the whole of [Evidence 1](#1-concurrency-model--thread-per-client-vs-thread-pool)
  as an assertion: six meters through a pool of two, and exactly two are served. Mocking the
  handler would have made both strategies look identical, because what differs between them
  is a thread blocking on a real stream.
- `ScenarioTest` asserts that every fault the `incident` scenario scripts actually crosses
  the threshold it is aimed at, and that the threshold-edit cue leaves the overload running.
  Both of those were wrong at some point while Phase 4 was being written, and neither is
  visible by reading the timeline.
- `LatencyRecorderTest` covers the percentile arithmetic behind Evidence 1's latency columns,
  including that eight threads recording at once lose no samples. An off-by-one in a
  percentile index produces a number that still looks like a latency.
- `LiveChartPanelTest` covers the strip chart's totals and its staleness rule without a
  display, by passing the instant to judge against rather than reading the clock.

### Benchmarks

```bash
mvn exec:java -Dexec.mainClass=com.smarthome.energy.bench.IngestBenchmark \
    -Dexec.args="--meters 10,50,200 --duration 60s --csv bench-results/ingest.csv"
mvn exec:java -Dexec.mainClass=com.smarthome.energy.bench.JdbcBatchBenchmark \
    -Dexec.args="--rows 50000 --csv bench-results/jdbc-batch.csv"
docker compose exec -T mysql mysql -u energy_app -pchange_me smart_home_energy \
    < sql/explain_index.sql
cd python && python -m analytics.benchmark --csv ../bench-results/python-speedup.csv
```

Each writes a CSV as well as printing its table, so the numbers in
[Engineering evidence](#engineering-evidence) are transcribed from a run rather than retyped
from a terminal. The JDBC benchmark stamps every row it writes inside a sentinel time window
in the year 2000 and deletes exactly that window afterwards, so half a million benchmark rows
never end up distorting the analytics or the index comparison. `sql/explain_index.sql`
likewise restores the index it drops.

### Running the failure demonstrations

```bash
./scripts/demo.sh --failure lost-update      # or frozen-ui, sql-injection, partial-write
mvn exec:java -Dexec.mainClass=com.smarthome.energy.demo.FailureDemos -Dexec.args="--all"
```

`lost-update` needs nothing; `sql-injection` and `partial-write` need MySQL; `frozen-ui`
additionally needs a display, and says so rather than throwing a `HeadlessException` out of a
script. Each writes only to its own throwaway table or its own sentinel time window and
cleans up before it returns.

---

## Directory layout

```
smart-home-energy-monitor/
├── pom.xml                         Maven build: Java 17, mysql-connector-j, exec targets
├── docker-compose.yml              MySQL 8 with schema + seed applied on first boot
├── LICENSE                         MIT license
├── README.md                       This document
├── .gitignore                      Java, Python, and IDE artifacts to exclude
├── scripts/
│   └── demo.sh                     Starts the stack; runs scripted scenarios and failure demos
├── sql/                            Database scripts (load in order: schema, then seed)
│   ├── schema.sql                  Database and table definitions
│   ├── seed.sql                    Seed devices and detection thresholds
│   └── explain_index.sql           Evidence 3: the history query planned with and without
│                                   the composite index, which it restores afterwards
├── docs/                           Report and design notes
│   ├── DESIGN.md                   Design-decision rationale (for the viva)
│   ├── REPORT.md                   The design-thinking report, with the measured evidence
│   └── evidence/                   Unedited output of the four measurement runs
├── python/                         Python analytics module root
│   ├── requirements.txt            Analytics dependencies
│   ├── analytics/                  The analytics package (multiprocessing)
│   │   ├── config.py               MySQL settings from ENERGY_DB_* with local defaults
│   │   ├── db.py                   Read-only history queries, one connection per worker
│   │   ├── device_trends.py        The per-device analysis mapped across the pool
│   │   ├── peak_hours.py           Whole-home hour-of-day demand profile
│   │   ├── cost_model.py           Time-of-use tariff, bill, and load-shift savings
│   │   ├── runner.py               Orchestration and report rendering
│   │   └── benchmark.py            Evidence 4: serial vs. Pool timing, and a check that
│   │                               both produced the same summaries
│   └── tests/                      Analytics unit tests (no database required)
└── src/
    ├── main/
    │   ├── resources/              Runtime configuration (db.properties)
    │   └── java/com/smarthome/energy/
    │       ├── model/              Shared domain value objects and enums
    │       ├── protocol/           Wire format, the validating DFA, and the parser
    │       ├── server/             Multithreaded TCP ingest server + accept strategies
    │       ├── db/                 JDBC connection factory and DAOs
    │       ├── rules/              Rule-based power-quality detection engine
    │       ├── simulator/          Meter simulators (TCP clients) and scenario playback
    │       ├── bench/              Ingest and JDBC benchmark harnesses
    │       ├── demo/               The four failure-mode demonstrations, broken beside fixed
    │       └── client/             Swing dashboard root (entry point + networking/data)
    │           ├── model/          MVC model: observable dashboard state
    │           ├── view/           MVC view: Swing window and panels
    │           └── controller/     MVC controller: mediates data and view
    └── test/
        └── java/                   Unit tests, incl. the DFA suite and the fuzz comparison
```

---

## Milestones

- **Phase 1 — Persistence foundation. Done.** MySQL schema in place, Docker Compose bringing
  it up reproducibly, and a single-client JDBC CRUD path working (insert a reading, read it
  back). Establishes the `db` layer and the schema.
- **Phase 2 — Live pipeline. Done.** The wire protocol and the validating DFA; TCP sockets
  and the multithreaded, thread-per-client server; simulators streaming; readings flowing
  live into the dashboard. Establishes the `server`, `simulator`, `protocol`, and `client`
  paths end to end. **This is the point at which the project is complete against its core
  specification**, less the detection that Phase 3 adds.
- **Phase 3 — Detection and analytics. Done.** The strategy-based rule engine and its
  reloadable threshold context; alerts written with their reading in one transaction, and
  published to the dashboard as a second frame type on the live feed; the Python
  multiprocessing analytics, cost model, and peak-hour report. Completes the graded core.
- **Phase 4 — Evidence and polish. Done.** The Layer 2 additions: the swappable accept
  strategy and the two Java benchmark harnesses; the four
  [Engineering evidence](#engineering-evidence) measurements, all taken; the four
  failure-mode demonstrations; the remaining three dashboard panels and the `RELOAD` command
  the threshold editor needed; the scripted `incident` scenario and `scripts/demo.sh`; and
  the design-thinking report in [`docs/REPORT.md`](docs/REPORT.md).

The DFA fuzz comparison was originally listed under Phase 4. It moved forward to Phase 2:
it is the evidence that the automaton recognises the right language, and the automaton is
what every reading in Phase 2 already passes through — deferring the check would have meant
building three more phases on an unverified recogniser.

Phases 1–3 are the graded core; Phase 4 is what supports the Analyze/Evaluate level of the
rubric. Nothing in Phase 4 was started before Phase 3 ran end to end, and the order turned
out to matter more than expected: Evidence 1 needed a second accept strategy that did not
exist, and building one made a claim in `DESIGN.md` measurable for the first time — at which
point it turned out to be [wrong in an interesting
way](#where-the-measurements-contradicted-the-design).

The report is framed as a design-thinking narrative — empathize, define, ideate, prototype,
test — with the Phase 4 measurements supplying the "test" evidence.

---

## Design questions and where they are answered

The viva is design-justification heavy. Every significant choice in this system has a
recorded rationale and, where a tradeoff is quantitative, a measurement behind it.
[`docs/DESIGN.md`](docs/DESIGN.md) holds the rationale decision by decision;
[`docs/REPORT.md`](docs/REPORT.md) is the narrative — how the problem was framed, what was
rejected, and what the evidence changed.

| Question | Answered by |
| -------- | ----------- |
| Why TCP rather than UDP for the meter streams? | [`docs/DESIGN.md`](docs/DESIGN.md) |
| Why thread-per-client rather than a thread pool? | [Evidence 1](#1-concurrency-model--thread-per-client-vs-thread-pool) — measured, and not for the reason [`docs/DESIGN.md`](docs/DESIGN.md) originally gave: a pool cannot help when the task holds its thread for the connection's lifetime |
| Did any measurement change your mind? | [Where the measurements contradicted the design](#where-the-measurements-contradicted-the-design) — two of the four refuted a prediction, and both predictions are still printed next to the result |
| Why is the rule engine off the ingest path? | [`docs/DESIGN.md`](docs/DESIGN.md) |
| Why are persistence and detection one sink rather than two? | [`docs/DESIGN.md`](docs/DESIGN.md) — the event's foreign key is the reading's generated id, so the pair must share a transaction |
| Why do the three rules use different severity cut-offs? | [Severity](#severity) — 5% out of band is a supply fault; 5% over a rated load is a motor starting |
| Why does an alert need a second frame type? | [The alert frame](#the-alert-frame) — the cost `DESIGN.md` predicted for reusing the meter format on the feed |
| What happens when a consumer cannot keep up with ingest? | [`docs/DESIGN.md`](docs/DESIGN.md) — the queue is bounded and drops on purpose, and every drop is counted |
| Why does the dashboard live feed reuse the meter wire format? | [`docs/DESIGN.md`](docs/DESIGN.md) — one grammar, one parser, verified once |
| Why does `DashboardModel` throw if touched off the EDT? | [Dashboard](#dashboard) — the alternative is a repaint that goes missing once an hour on someone else's machine |
| Why MySQL rather than flat files? | [`docs/DESIGN.md`](docs/DESIGN.md) |
| Why a hand-written DFA rather than a regular expression? | [The validating DFA](#the-validating-dfa) — a regex compiles to one anyway; writing it explicitly makes the automaton inspectable and yields the error position for free |
| How do you know the DFA is correct? | [Verifying the automaton](#verifying-the-automaton) — randomised equivalence against a reference regex |
| Why `multiprocessing` rather than `threading` in Python? | [Evidence 4](#4-python-parallel-speedup) — the measured speedup and the GIL, with a note on why *this* workload would also have parallelised under threads |
| How is the threshold editor's change picked up by a server that is already running? | [Dashboard](#dashboard) and [Rule engine detection logic](#rule-engine-detection-logic) — it commits to the table and then sends `RELOAD` up the live feed; the alternative, polling, makes an edit take effect at no particular moment |
| Why does every DAO use `PreparedStatement`? | [Failure-mode demonstrations](#failure-mode-demonstrations) — the injection demo |
| Why `SwingWorker` rather than querying directly? | [Failure-mode demonstrations](#failure-mode-demonstrations) — the frozen-UI demo |
| Why is the `(device_id, reading_ts)` index there? | [Evidence 3](#3-index-effectiveness) — the `EXPLAIN` comparison |
| Show the system handling a fault. | [The scripted demo](#the-scripted-demo) |

---

## Team and ownership

Team of three. Ownership is split so each member owns a coherent slice that spans the
syllabus units — and so that each member can defend every line in their own packages.

| Member | GitHub | Owns | Packages / files |
| ------ | ------ | ---- | ---------------- |
| Anmol Goyal | `anmol-goyal7` | Socket server, threading model and benchmark, JDBC layer, schema | `server`, `db`, `protocol`, `bench`, `sql/` |
| Bhumika Rajput | `BhumikaRajput28` | Swing MVC client, threshold editor, EDT demonstration | `client` (and its `model`/`view`/`controller`) |
| Jiya Nambiar | `jiyanambiar` | Meter simulators, scenario playback, rule engine, Python analytics, report | `simulator`, `rules`, `python/`, `docs/` |

---

## License

Released under the [MIT License](LICENSE).
