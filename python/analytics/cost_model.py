"""Time-of-use cost model: what the measured consumption actually costs, and what it need not.

Applies a banded tariff to the hour-of-day profile the readings produce, and reports per
device: energy over a representative day, the monthly bill that implies, how much of it is
incurred inside the peak band, and what could be saved by running that load off-peak
instead.

**Why this exists.** "The water heater averages 1.8 kW between 18:00 and 21:00" is a fact
nobody can act on. "Those three hours are ₹340 of a ₹900 monthly bill, and ₹150 of that goes
away if it runs at 02:00" is a decision. The analytics module is where telemetry becomes a
recommendation, and this is the part that does it.

**Tariff bands are configuration, not code** — :data:`DEFAULT_TARIFF` is a plausible Indian
domestic time-of-use schedule, and a different supplier is a different :class:`Tariff`
passed in, not an edit to this file.

Syllabus mapping: Unit IV — Python scripting (the actionable analysis).
"""

from dataclasses import dataclass

from analytics.peak_hours import HOURS_IN_DAY, average_load_by_device_hour

#: Days used to turn a representative day's energy into a monthly bill.
DAYS_PER_MONTH = 30


@dataclass(frozen=True)
class TariffBand:
    """One priced stretch of the day.

    ``start_hour`` is inclusive, ``end_hour`` exclusive, and a band may wrap past midnight
    (``22 -> 6``), because that is how off-peak windows are actually written.
    """

    name: str
    start_hour: int
    end_hour: int
    rate_per_kwh: float

    def covers(self, hour):
        """Whether this band prices the given hour of the day."""
        if self.start_hour <= self.end_hour:
            return self.start_hour <= hour < self.end_hour
        return hour >= self.start_hour or hour < self.end_hour


@dataclass(frozen=True)
class Tariff:
    """A full day's worth of bands, plus the currency they are quoted in."""

    currency: str
    bands: tuple

    def band_for(self, hour):
        """Return the band pricing an hour, or None if the bands leave it uncovered."""
        for band in self.bands:
            if band.covers(hour):
                return band
        return None

    def rate_for(self, hour):
        """Return the price per kWh at an hour of the day.

        :raises ValueError: if no band covers that hour — an incomplete tariff would
            otherwise silently price part of the day at zero.
        """
        band = self.band_for(hour)
        if band is None:
            raise ValueError(f"tariff has no band covering hour {hour}")
        return band.rate_per_kwh

    def cheapest_band(self):
        """Return the band with the lowest rate: where a shiftable load would rather run."""
        return min(self.bands, key=lambda band: band.rate_per_kwh)

    def peak_band(self):
        """Return the band with the highest rate."""
        return max(self.bands, key=lambda band: band.rate_per_kwh)

    def validate(self):
        """Check that the bands price all 24 hours exactly once.

        :raises ValueError: naming the first hour that is uncovered or double-covered.
        """
        for hour in range(HOURS_IN_DAY):
            covering = [band.name for band in self.bands if band.covers(hour)]
            if len(covering) != 1:
                raise ValueError(
                    f"hour {hour} is covered by {covering or 'no band'}, expected exactly one"
                )
        return self


#: A plausible domestic time-of-use schedule, in rupees per kWh.
DEFAULT_TARIFF = Tariff(
    currency="INR",
    bands=(
        TariffBand("off-peak", 22, 6, 4.50),
        TariffBand("standard", 6, 18, 6.50),
        TariffBand("peak", 18, 22, 9.00),
    ),
)


@dataclass(frozen=True)
class DeviceCost:
    """What one device's measured consumption costs under a tariff."""

    device_id: int
    daily_kwh: float
    monthly_cost: float
    peak_kwh: float
    """Energy drawn inside the most expensive band, over a representative day."""

    peak_monthly_cost: float
    shift_saving_monthly: float
    """What moving that peak-band energy into the cheapest band would save each month."""

    @property
    def peak_share(self):
        """Fraction of this device's daily energy drawn in the peak band."""
        return self.peak_kwh / self.daily_kwh if self.daily_kwh else 0.0


def energy_by_device_hour(readings):
    """Return ``{(device_id, hour): kwh}`` for a representative day.

    A device's average load in an hour, held for that hour, is the energy it uses in it —
    so the hour-of-day profile converts directly into kWh with no further assumptions.

    :param readings: rows with ``device_id``, ``reading_ts`` and ``power_watts``.
    """
    return {
        key: average_watts / 1000.0
        for key, (average_watts, _) in average_load_by_device_hour(readings).items()
    }


def estimate_costs(readings, tariff=DEFAULT_TARIFF, days_per_month=DAYS_PER_MONTH):
    """Price every device's measured consumption.

    :param readings: rows with ``device_id``, ``reading_ts`` and ``power_watts``.
    :param tariff: the band schedule to price against.
    :param days_per_month: days a monthly bill is billed over.
    :return: a list of :class:`DeviceCost`, heaviest bill first.
    :raises ValueError: if the tariff leaves an hour of the day unpriced.
    """
    tariff.validate()
    peak_band = tariff.peak_band()
    saving_per_kwh = peak_band.rate_per_kwh - tariff.cheapest_band().rate_per_kwh

    daily_kwh = {}
    daily_cost = {}
    peak_kwh = {}
    for (device_id, hour), kwh in energy_by_device_hour(readings).items():
        daily_kwh[device_id] = daily_kwh.get(device_id, 0.0) + kwh
        daily_cost[device_id] = daily_cost.get(device_id, 0.0) + kwh * tariff.rate_for(hour)
        if peak_band.covers(hour):
            peak_kwh[device_id] = peak_kwh.get(device_id, 0.0) + kwh

    costs = [
        DeviceCost(
            device_id=device_id,
            daily_kwh=kwh,
            monthly_cost=daily_cost[device_id] * days_per_month,
            peak_kwh=peak_kwh.get(device_id, 0.0),
            peak_monthly_cost=peak_kwh.get(device_id, 0.0)
            * peak_band.rate_per_kwh
            * days_per_month,
            shift_saving_monthly=peak_kwh.get(device_id, 0.0)
            * saving_per_kwh
            * days_per_month,
        )
        for device_id, kwh in daily_kwh.items()
    ]

    return sorted(costs, key=lambda cost: (-cost.monthly_cost, cost.device_id))


def total_monthly_cost(costs):
    """Return the whole home's monthly bill.

    :param costs: the list :func:`estimate_costs` returned.
    """
    return sum(cost.monthly_cost for cost in costs)


def recommend_shift(costs, deferrable_ids, minimum_saving=1.0):
    """Return the deferrable devices worth moving out of the peak band, best first.

    Only deferrable loads are recommended: a refrigerator's peak-band energy is expensive
    and cannot be rescheduled, and a report that suggests running it at 02:00 is a report
    that gets ignored wholesale.

    :param costs: the list :func:`estimate_costs` returned.
    :param deferrable_ids: device ids whose load can be rescheduled.
    :param minimum_saving: the smallest monthly saving worth printing a line about.
    """
    candidates = [
        cost
        for cost in costs
        if cost.device_id in set(deferrable_ids)
        and cost.shift_saving_monthly >= minimum_saving
    ]
    return sorted(candidates, key=lambda cost: -cost.shift_saving_monthly)
