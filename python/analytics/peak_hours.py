"""Whole-home peak-hour demand analysis.

Buckets every reading by hour-of-day (00..23), averages the real power across all devices
in each bucket, and identifies the hours at which the home draws the most power. Answers
the question "when does this household peak?" — useful for load shifting and for the
report's demand narrative.

**Why the average is taken per device and then summed.** The obvious aggregation — add up
every ``power_watts`` in the bucket and divide by the number of rows — answers a question
nobody asked: it gives the average draw of a *typical appliance* at that hour, and a meter
that reports twice as often as its neighbours pulls the answer towards itself. What the
home actually draws at 19:00 is the sum over appliances of what each of them draws, so each
device is averaged over its own samples first and the averages are added. The result is a
load in watts that is comparable between hours no matter how the sampling varied.

Syllabus mapping: Unit IV — Python scripting (aggregation over history).
"""

from dataclasses import dataclass

#: Hours in the profile — every one is present, including the ones with no data.
HOURS_IN_DAY = 24


@dataclass(frozen=True)
class HourProfile:
    """One hour-of-day bucket of the whole-home demand profile."""

    hour: int
    """The hour of the day this bucket covers, 0..23."""

    average_watts: float
    """Whole-home load in that hour: the sum over devices of each device's own average."""

    energy_kwh: float
    """What that load consumes over one such hour, in kWh."""

    sample_count: int
    """Readings that fell in this bucket, across every device."""

    device_count: int
    """Distinct devices that reported in this bucket."""

    @property
    def has_data(self):
        """Whether any reading fell in this hour."""
        return self.sample_count > 0


def average_load_by_device_hour(readings):
    """Return ``{(device_id, hour): (average_watts, sample_count)}``.

    The shared bucketing step: :func:`compute_peak_hours` sums these across devices and
    :mod:`analytics.cost_model` prices them per device, so the two cannot disagree about
    what the load was.

    :param readings: rows with ``device_id``, ``reading_ts`` and ``power_watts``.
    """
    totals = {}
    for row in readings:
        key = (row["device_id"], row["reading_ts"].hour)
        watts, count = totals.get(key, (0.0, 0))
        totals[key] = (watts + float(row["power_watts"]), count + 1)

    return {key: (watts / count, count) for key, (watts, count) in totals.items()}


def compute_peak_hours(readings):
    """Aggregate readings into a 24-slot hour-of-day power profile.

    :param readings: rows with ``device_id``, ``reading_ts`` and ``power_watts``.
    :return: a list of 24 :class:`HourProfile`, hour 0 first. Hours with no readings are
        present and empty, so a profile is always comparable with another profile.

    Pure function over the rows passed in, so it is testable without a database.
    """
    per_device = average_load_by_device_hour(readings)

    load = [0.0] * HOURS_IN_DAY
    samples = [0] * HOURS_IN_DAY
    devices = [0] * HOURS_IN_DAY
    for (_, hour), (average_watts, count) in per_device.items():
        load[hour] += average_watts
        samples[hour] += count
        devices[hour] += 1

    return [
        HourProfile(
            hour=hour,
            average_watts=load[hour],
            energy_kwh=load[hour] / 1000.0,
            sample_count=samples[hour],
            device_count=devices[hour],
        )
        for hour in range(HOURS_IN_DAY)
    ]


def rank_peak_hours(profile, top=3):
    """Return the busiest hours of a profile, heaviest first.

    :param profile: the list :func:`compute_peak_hours` returned.
    :param top: how many hours to return.
    :return: at most ``top`` hours that actually have data, ordered by load descending and
        by hour ascending where two hours draw the same.
    """
    with_data = [hour for hour in profile if hour.has_data]
    return sorted(with_data, key=lambda h: (-h.average_watts, h.hour))[:top]


def daily_energy_kwh(profile):
    """Return the whole home's energy over a representative day, in kWh.

    :param profile: the list :func:`compute_peak_hours` returned.
    """
    return sum(hour.energy_kwh for hour in profile)
