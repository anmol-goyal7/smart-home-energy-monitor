"""Evidence 4: what ``multiprocessing`` is actually worth here.

``python -m analytics`` fans the per-device trend analysis out across a
``multiprocessing.Pool`` rather than a ``ThreadPool``, and the stated reason is the GIL.
That is a claim about behaviour, so this module measures it: the same work is run serially
and then through :func:`analytics.runner.analyse_devices` at several worker counts, and the
wall-clock times are printed side by side.

    python -m analytics.benchmark

The parallel rows call the real ``analyse_devices``, pool construction included, because
pool construction is a cost the report pays every time it runs — and on Python 3.14, whose
default start method on Linux is ``forkserver`` rather than ``fork``, it is not a small one.
Timing ``pool.map`` alone would flatter the parallel rows by hiding a cost the caller cannot
avoid.

What the curve is expected to do, and why it is worth printing rather than hiding: the
per-device analysis is IO-bound on MySQL, not CPU-bound, so the speedup should flatten once
the workers outnumber the connections the database will usefully serve at once. A sub-linear
curve is the honest result for this workload, and the point of measuring is to be able to
say which part of the curve this system sits on.

Syllabus mapping: Unit IV — Python scripting and multiprocessing (measuring the parallelism).
"""

import argparse
import csv
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timedelta

from analytics import config, db, device_trends, runner

#: Worker counts measured when ``--processes`` says nothing.
DEFAULT_WORKER_COUNTS = (2, 4, 8)

#: How many times the device list is repeated to make one unit of work per entry.
DEFAULT_REPEAT = 1


@dataclass(frozen=True)
class Timing:
    """One row of the results table.

    :param mode: ``"Serial"`` or ``"multiprocessing.Pool"``.
    :param workers: worker processes used; 1 for the serial baseline.
    :param seconds: wall clock for the whole run, pool construction included.
    :param speedup: this row's throughput relative to the serial baseline.
    :param units: how many per-device analyses the run performed.
    """

    mode: str
    workers: int
    seconds: float
    speedup: float
    units: int


def run(argv=None):
    """Measure serial against pooled analysis and print the comparison.

    :param argv: command-line arguments; defaults to ``sys.argv[1:]``.
    :return: a process exit status — 0 on success, 1 if the database could not be read.
    """
    options = _parse_args(argv)
    since = None if options.hours is None else datetime.now() - timedelta(hours=options.hours)

    try:
        device_ids = db.fetch_device_ids()
    except Exception as failure:  # noqa: BLE001 - the CLI reports, it does not re-raise
        print(f"benchmark: could not read the database ({failure})", file=sys.stderr)
        print("benchmark: is MySQL up (docker compose up -d), and are the ENERGY_DB_* "
              "settings right?", file=sys.stderr)
        return 1

    if not device_ids:
        print("benchmark: the devices table is empty; load sql/seed.sql first.", file=sys.stderr)
        return 1

    work = device_ids * options.repeat
    print()
    print("Smart Home Energy Monitor — analytics parallel benchmark")
    print(f"  database   : {config.describe(config.load_db_config())}")
    print(f"  devices    : {len(device_ids)}")
    print(f"  work units : {len(work)} per-device analyses "
          f"({len(device_ids)} device(s) x {options.repeat})")
    print(f"  window     : {'all history' if since is None else f'the last {options.hours} h'}")
    print()

    # An untimed pass, so that neither row pays for a cold page cache in MySQL or a
    # first-call import in this process. Without it the serial baseline — which runs first —
    # absorbs a cost the parallel rows never see, and every speedup below is overstated.
    print("  warming up …")
    baseline_results = _run_serial(work, since)

    timings = []
    serial_seconds = _time(lambda: _run_serial(work, since))
    timings.append(Timing("Serial", 1, serial_seconds, 1.0, len(work)))
    print(f"  serial                      {serial_seconds:6.2f} s")

    for workers in options.processes:
        seconds = _time(lambda w=workers: runner.analyse_devices(work, since, processes=w))
        timings.append(Timing("multiprocessing.Pool", workers, seconds,
                              serial_seconds / seconds if seconds > 0 else 0.0, len(work)))
        print(f"  multiprocessing.Pool({workers:<2})    {seconds:6.2f} s")

    # A speedup is only interesting if the fast answer is the same answer. The pool pickles
    # its results back from another process, so this also checks that the round trip through
    # the pickle preserved them.
    parallel_results = runner.analyse_devices(work, since, processes=max(options.processes))
    _report_agreement(baseline_results, parallel_results)

    print()
    _print_table(timings)
    if options.csv:
        _write_csv(options.csv, timings)
    return 0


def _run_serial(device_ids, since):
    """Analyse every device one after another in this process — the baseline.

    Calls the same :func:`analytics.device_trends.analyze_device` the pool maps, so the two
    rows differ in how the work is scheduled and in nothing else. Each call opens its own
    connection, exactly as it does inside a worker.
    """
    return [device_trends.analyze_device(device_id, since=since) for device_id in device_ids]


def _time(operation):
    """Return the wall-clock seconds ``operation`` took.

    ``perf_counter`` rather than ``time.time``: it is monotonic, so an NTP correction in the
    middle of a sixty-second run cannot produce a negative duration.
    """
    start = time.perf_counter()
    operation()
    return time.perf_counter() - start


def _report_agreement(serial_results, parallel_results):
    """Print whether the pooled run produced the same summaries as the serial one."""
    if serial_results == parallel_results:
        print(f"  results agree with the serial baseline ({len(serial_results)} summaries)")
        return
    mismatches = sum(1 for a, b in zip(serial_results, parallel_results) if a != b)
    print(f"  WARNING: {mismatches} summary/summaries differ between the serial and pooled "
          f"runs. Readings arriving mid-benchmark will do this; a difference with the "
          f"server stopped would not be benign.")


def _print_table(timings):
    """Render the results as the markdown table the README's Evidence 4 carries."""
    print("| Mode | Workers | Wall clock (s) | Speedup |")
    print("| ---- | ------- | -------------- | ------- |")
    for timing in timings:
        print(f"| {timing.mode} | {timing.workers} | {timing.seconds:.2f} "
              f"| {timing.speedup:.2f}x |")


def _write_csv(path, timings):
    """Write the same rows to a file, so the README's table is transcribed, not retyped."""
    try:
        with open(path, "w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle)
            writer.writerow(["mode", "workers", "seconds", "speedup", "units"])
            for timing in timings:
                writer.writerow([timing.mode, timing.workers, f"{timing.seconds:.3f}",
                                 f"{timing.speedup:.3f}", timing.units])
        print(f"benchmark: wrote {len(timings)} row(s) to {path}")
    except OSError as failure:
        print(f"benchmark: could not write {path} ({failure})", file=sys.stderr)


def _parse_args(argv):
    parser = argparse.ArgumentParser(
        prog="python -m analytics.benchmark",
        description="Measure the speedup multiprocessing gives the per-device analysis.",
    )
    parser.add_argument(
        "--processes",
        type=_worker_counts,
        default=list(DEFAULT_WORKER_COUNTS),
        help="comma-separated worker counts to measure (default: 2,4,8)",
    )
    parser.add_argument(
        "--repeat",
        type=int,
        default=DEFAULT_REPEAT,
        help="repeat the device list this many times, to give the pool more work units "
             "than there are devices (default: 1)",
    )
    parser.add_argument(
        "--hours",
        type=int,
        default=None,
        help="only analyse the last N hours (default: all stored history)",
    )
    parser.add_argument(
        "--csv",
        default=None,
        help="also write the results to this path as CSV",
    )
    options = parser.parse_args(argv)
    if options.repeat < 1:
        parser.error("--repeat must be at least 1")
    return options


def _worker_counts(raw):
    """Parse ``2,4,8`` into ``[2, 4, 8]``, rejecting anything that is not a positive count."""
    counts = []
    for part in raw.split(","):
        part = part.strip()
        if not part:
            continue
        try:
            count = int(part)
        except ValueError:
            raise argparse.ArgumentTypeError(
                f"--processes takes whole numbers, got {part!r}"
            ) from None
        if count < 1:
            raise argparse.ArgumentTypeError("--processes values must be at least 1")
        counts.append(count)
    if not counts:
        raise argparse.ArgumentTypeError("--processes needs at least one worker count")
    return counts


if __name__ == "__main__":
    # Required for multiprocessing: a worker re-imports this module, and without the guard
    # it would re-run the benchmark inside every worker it started.
    sys.exit(run(sys.argv[1:]))
