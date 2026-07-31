"""Per-device usage trend analysis — the unit of work parallelised across processes.

Each call analyses the history of exactly one device: average power, min/max, duty-cycle
estimate, and a simple direction-of-trend (rising/steady/falling) over the window. Because
the work partitions cleanly by device, the runner maps this function over all devices with
a ``multiprocessing.Pool`` — one worker process per device.

The work splits into two pieces on purpose. :func:`analyze_device` is the worker: it opens
a connection, fetches, and hands off. :func:`summarise` is the arithmetic, and it is a pure
function over rows — which is what makes the analysis testable without a database, and what
keeps the part that can be wrong separate from the part that needs MySQL to run at all.

Syllabus mapping: Unit IV — Python scripting and multiprocessing (the mapped worker).
"""

from dataclasses import dataclass

from analytics import db

#: A device counts as "on" while it draws at least this fraction of its own peak.
DUTY_ON_FRACTION = 0.20

#: The second half of the window must differ from the first by more than this to be a trend.
TREND_TOLERANCE = 0.05


@dataclass(frozen=True)
class DeviceTrend:
    """One device's usage summary over the analysed window.

    A frozen dataclass rather than a dict because it crosses a process boundary: the pickle
    that comes back names its fields, so a worker returning the wrong shape is a failure in
    the parent rather than a ``KeyError`` three tables later.
    """

    device_id: int
    sample_count: int
    average_watts: float
    min_watts: float
    max_watts: float
    duty_cycle: float
    """Fraction of samples drawing at least :data:`DUTY_ON_FRACTION` of this device's peak."""

    direction: str
    """``"rising"``, ``"falling"``, or ``"steady"`` — the second half against the first."""

    change_fraction: float
    """How much the second half of the window differs from the first, as a fraction."""

    @property
    def has_data(self):
        """Whether this device reported anything in the window."""
        return self.sample_count > 0


def summarise(device_id, readings):
    """Compute the trend summary for one device's rows. Pure; no database involved.

    :param device_id: the device the rows belong to.
    :param readings: that device's rows, oldest first, each with ``power_watts``.
    """
    watts = [float(row["power_watts"]) for row in readings]
    if not watts:
        return DeviceTrend(device_id, 0, 0.0, 0.0, 0.0, 0.0, "steady", 0.0)

    peak = max(watts)
    on_threshold = peak * DUTY_ON_FRACTION
    running = sum(1 for w in watts if w >= on_threshold)

    direction, change = _direction(watts)

    return DeviceTrend(
        device_id=device_id,
        sample_count=len(watts),
        average_watts=sum(watts) / len(watts),
        min_watts=min(watts),
        max_watts=peak,
        duty_cycle=running / len(watts),
        direction=direction,
        change_fraction=change,
    )


def analyze_device(device_id, since=None):
    """Compute the trend summary for a single device and return it.

    Runs inside a worker process, so it opens its own database connection (via
    :mod:`analytics.db`), fetches that device's readings, computes the summary, and returns
    a plain, picklable result the parent process can collect.

    :param device_id: the device to analyse.
    :param since: a ``datetime`` lower bound on ``reading_ts``, or None for all history.
    """
    return summarise(device_id, db.fetch_readings_for_device(device_id, since))


def _direction(watts):
    """Compare the second half of the window with the first.

    A least-squares slope would be the textbook answer and is harder to explain than it is
    worth here: the question is "is this appliance using more power than it was", and the
    difference between the two halves answers it in terms anyone reading the report can
    check by eye. A change smaller than :data:`TREND_TOLERANCE` is called steady, because
    telemetry noise alone will move the halves by a percent or two.
    """
    if len(watts) < 2:
        return "steady", 0.0

    midpoint = len(watts) // 2
    first = watts[:midpoint]
    second = watts[midpoint:]
    first_average = sum(first) / len(first)
    second_average = sum(second) / len(second)

    if first_average == 0.0:
        return ("rising", 1.0) if second_average > 0.0 else ("steady", 0.0)

    change = (second_average - first_average) / first_average
    if abs(change) <= TREND_TOLERANCE:
        return "steady", change
    return ("rising" if change > 0 else "falling"), change
