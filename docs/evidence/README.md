# Raw measurement output

The unedited output of the four Phase 4 measurements, kept so the tables in the project
README can be checked against what the harnesses actually printed rather than taken on trust.

Everything here was produced in one sitting on the machine described under
[Measurement conditions](../../README.md#measurement-conditions), against ~113,000 stored
readings across the six seeded devices.

| File | Produced by | Backs |
| ---- | ----------- | ----- |
| `ingest.txt`, `ingest.csv` | `bench.IngestBenchmark --meters 10,50,200 --duration 60s --warmup 5s --interval 100` | [Evidence 1](../../README.md#1-concurrency-model--thread-per-client-vs-thread-pool) |
| `jdbc-batch.txt`, `jdbc-batch.csv` | `bench.JdbcBatchBenchmark --rows 50000 --batch 500 --pool 4` | [Evidence 2](../../README.md#2-jdbc-insert-strategy) |
| `explain-index.txt` | `sql/explain_index.sql` | [Evidence 3](../../README.md#3-index-effectiveness) |
| `python-speedup.txt`, `python-speedup.csv` | `python -m analytics.benchmark --processes 2,4,8` | [Evidence 4](../../README.md#4-python-parallel-speedup) |

`bench-results/` at the repository root is where the harnesses write by default and is
git-ignored; this directory is the curated copy that the documentation cites. Re-running a
harness overwrites the former and leaves the latter alone, so a fresh run cannot silently
change the numbers a reader is checking.

Two things a reader should know before comparing these to their own run:

- **The ingest figures are harness-bound.** The load generators share a JVM and cores with
  the server, so absolute throughput is capped by the harness rather than by the server. The
  comparison between the two strategies is unaffected — both pay the same cost.
- **The lost-update demonstration is not in here, and could not usefully be.** Its output
  differs on every run by design; the figure quoted in the README (58% of increments lost) is
  one run on this machine, and the fact that it is not reproducible is the finding.
