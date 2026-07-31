"""Orchestrates the analytics run.

This is the coordinator that ties the module together:

1. read the device list from the database;
2. fan the per-device trend analysis out across a ``multiprocessing.Pool`` — mapping
   :func:`analytics.device_trends.analyze_device` over every device id so the workers run
   in parallel, one process per device;
3. run the whole-home :func:`analytics.peak_hours.compute_peak_hours` analysis;
4. price the same history with :mod:`analytics.cost_model`;
5. render the results as tables (``tabulate``) and print the report.

The parallel step is the point of the exercise: independent, CPU-light-but-IO-bound
per-device scans that the process pool overlaps instead of running one after another. Each
worker opens its own connection, because a MySQL connection is a socket and a socket
inherited by two processes is a protocol error waiting to happen.

Syllabus mapping: Unit IV — Python scripting and multiprocessing (Pool orchestration).
"""

import argparse
import functools
import multiprocessing
import sys
from datetime import datetime, timedelta

from analytics import config, cost_model, db, device_trends, peak_hours

#: Appliance types whose load can be rescheduled, for the load-shifting recommendation.
DEFERRABLE_TYPES = {"WASHER", "HEATER", "DISHWASHER"}

#: Never start more workers than this, however many devices are configured.
MAX_WORKERS = 32


def run(argv=None):
    """Execute the full analytics pipeline and print the report.

    :param argv: command-line arguments; defaults to ``sys.argv[1:]``.
    :return: a process exit status — 0 on success, 1 if the database could not be read.
    """
    options = _parse_args(argv)
    since = None if options.hours is None else datetime.now() - timedelta(hours=options.hours)

    try:
        devices = db.fetch_devices()
        readings = db.fetch_all_readings(since)
        trends = analyse_devices([device["device_id"] for device in devices], since,
                                 processes=options.processes)
    except Exception as failure:  # noqa: BLE001 - the CLI reports, it does not re-raise
        print(f"analytics: could not read the database ({failure})", file=sys.stderr)
        print("analytics: is MySQL up (docker compose up -d), and are the ENERGY_DB_* "
              "settings right?", file=sys.stderr)
        return 1

    _print_report(devices, readings, trends, since, options)
    return 0


def analyse_devices(device_ids, since=None, processes=None, worker=device_trends.analyze_device):
    """Map the per-device analysis across a process pool, one worker per device.

    :param device_ids: the devices to analyse.
    :param since: a ``datetime`` lower bound on ``reading_ts``, or None for all history.
    :param processes: worker count; defaults to one per device, capped at
        :data:`MAX_WORKERS`.
    :param worker: the ``(device_id, since=...)`` callable to map. It must be importable by
        name, because it is pickled and sent to each process — which is also what lets the
        Phase 4 benchmark and the tests map something other than the real analysis.
    :return: the summaries, in the order the devices were given.
    """
    if not device_ids:
        return []

    worker_count = processes or min(len(device_ids), MAX_WORKERS)
    # functools.partial rather than a lambda or a closure: the callable is pickled and sent
    # to each worker, and only something importable by name survives that.
    mapped = functools.partial(worker, since=since)

    with multiprocessing.Pool(processes=worker_count) as pool:
        return pool.map(mapped, device_ids)


# ---------------------------------------------------------------------- rendering


def _print_report(devices, readings, trends, since, options):
    """Render the whole report to stdout."""
    names = {device["device_id"]: device["name"] for device in devices}
    deferrable = {
        device["device_id"]
        for device in devices
        if device["appliance_type"] in DEFERRABLE_TYPES
    }

    window = "all history" if since is None else f"the last {options.hours} h"
    print()
    print("Smart Home Energy Monitor — analytics report")
    print(f"  database : {config.describe(config.load_db_config())}")
    print(f"  window   : {window}")
    print(f"  readings : {len(readings)} across {len(devices)} device(s)")
    print()

    if not readings:
        print("No readings in this window — start the server and the simulators, let them "
              "run for a while, then try again.")
        return

    _print_device_trends(trends, names)
    _print_peak_hours(readings, options.top)
    _print_costs(readings, names, deferrable)


def _print_device_trends(trends, names):
    print("Per-device usage (analysed in parallel, one worker process per device)")
    rows = [
        [
            trend.device_id,
            names.get(trend.device_id, f"device {trend.device_id}"),
            trend.sample_count,
            f"{trend.average_watts:.1f}",
            f"{trend.min_watts:.1f}",
            f"{trend.max_watts:.1f}",
            f"{trend.duty_cycle * 100:.0f}%",
            f"{trend.direction} ({trend.change_fraction * 100:+.1f}%)",
        ]
        for trend in trends
        if trend.has_data
    ]
    print(_table(["id", "device", "samples", "avg W", "min W", "max W", "duty", "trend"], rows))
    print()


def _print_peak_hours(readings, top):
    profile = peak_hours.compute_peak_hours(readings)
    busiest = peak_hours.rank_peak_hours(profile, top)

    print(f"Peak demand hours (whole home, top {len(busiest)})")
    rows = [
        [
            f"{hour.hour:02d}:00–{(hour.hour + 1) % 24:02d}:00",
            f"{hour.average_watts:.1f}",
            f"{hour.energy_kwh:.3f}",
            hour.device_count,
            hour.sample_count,
        ]
        for hour in busiest
    ]
    print(_table(["hour", "avg W", "kWh", "devices", "samples"], rows))
    print(f"  whole-home consumption over a representative day: "
          f"{peak_hours.daily_energy_kwh(profile):.2f} kWh")
    print()


def _print_costs(readings, names, deferrable):
    tariff = cost_model.DEFAULT_TARIFF
    costs = cost_model.estimate_costs(readings, tariff)
    bands = ", ".join(
        f"{band.name} {band.start_hour:02d}–{band.end_hour:02d} @ {band.rate_per_kwh:.2f}"
        for band in tariff.bands
    )

    print(f"Time-of-use cost ({tariff.currency} per kWh: {bands})")
    rows = [
        [
            cost.device_id,
            names.get(cost.device_id, f"device {cost.device_id}"),
            f"{cost.daily_kwh:.2f}",
            f"{cost.monthly_cost:.2f}",
            f"{cost.peak_share * 100:.0f}%",
            f"{cost.shift_saving_monthly:.2f}",
        ]
        for cost in costs
    ]
    print(_table(["id", "device", "kWh/day", "cost/month", "in peak", "if shifted"], rows))
    print(f"  whole-home bill: {cost_model.total_monthly_cost(costs):.2f} "
          f"{tariff.currency}/month over {cost_model.DAYS_PER_MONTH} days")

    recommendations = cost_model.recommend_shift(costs, deferrable)
    if recommendations:
        cheapest = tariff.cheapest_band()
        print()
        print("Recommended load shifting")
        for cost in recommendations:
            name = names.get(cost.device_id, f"device {cost.device_id}")
            print(f"  - move {name} into the {cheapest.name} band "
                  f"({cheapest.start_hour:02d}:00–{cheapest.end_hour:02d}:00): saves "
                  f"{cost.shift_saving_monthly:.2f} {tariff.currency}/month")
    print()


def _table(headers, rows):
    """Render a table, using ``tabulate`` when it is installed and plain text when it is not.

    The dependency is in ``requirements.txt`` and the fallback is four lines, which is a
    cheaper price than a report that cannot print at all on a machine where the virtualenv
    was not activated.
    """
    if not rows:
        return "  (nothing to report)"

    try:
        from tabulate import tabulate

        return tabulate(rows, headers=headers, tablefmt="simple")
    except ModuleNotFoundError:
        return _plain_table(headers, rows)


def _plain_table(headers, rows):
    """Column-align a table with the standard library alone."""
    columns = [[str(header)] + [str(row[index]) for row in rows]
               for index, header in enumerate(headers)]
    widths = [max(len(cell) for cell in column) for column in columns]

    def line(cells):
        return "  ".join(str(cell).ljust(widths[index]) for index, cell in enumerate(cells))

    rendered = [line(headers), "  ".join("-" * width for width in widths)]
    rendered.extend(line(row) for row in rows)
    return "\n".join(rendered)


def _parse_args(argv):
    parser = argparse.ArgumentParser(
        prog="python -m analytics",
        description="Offline analytics over the smart home energy history.",
    )
    parser.add_argument(
        "--hours",
        type=int,
        default=None,
        help="only analyse the last N hours (default: all stored history)",
    )
    parser.add_argument(
        "--top",
        type=int,
        default=5,
        help="how many peak hours to list (default: 5)",
    )
    parser.add_argument(
        "--processes",
        type=int,
        default=None,
        help="worker processes to use (default: one per device)",
    )
    return parser.parse_args(argv)
