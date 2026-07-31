"""Unit tests for the analytics module's pure functions.

These cover the arithmetic that turns rows into the report: the hour-of-day bucketing, the
per-device trend summary, and the tariff. None of them touch MySQL — every function under
test takes rows and returns values, which is exactly why the module is split that way.

Run from the ``python/`` directory:

    python -m unittest discover -s tests -t .
"""

import unittest
from datetime import datetime

from analytics import config, cost_model, device_trends, peak_hours, runner


def reading(device_id, hour, watts, minute=0, day=24):
    """One reading row, shaped exactly as the ``SELECT`` returns it."""
    return {
        "device_id": device_id,
        "reading_ts": datetime(2026, 7, day, hour, minute, 0),
        "voltage": 230.0,
        "current_amp": watts / 230.0,
        "power_watts": watts,
    }


class PeakHoursTest(unittest.TestCase):

    def test_profile_always_covers_the_whole_day(self):
        profile = peak_hours.compute_peak_hours([reading(1, 9, 100.0)])

        self.assertEqual(24, len(profile))
        self.assertEqual(list(range(24)), [hour.hour for hour in profile])
        self.assertTrue(profile[9].has_data)
        self.assertFalse(profile[10].has_data)

    def test_whole_home_load_is_the_sum_of_per_device_averages(self):
        rows = [
            # The fridge samples three times in the hour, the HVAC once. Averaging the rows
            # together would report 400 W; the home is actually drawing 200 + 1000.
            reading(1, 19, 200.0, minute=0),
            reading(1, 19, 200.0, minute=20),
            reading(1, 19, 200.0, minute=40),
            reading(2, 19, 1000.0, minute=0),
        ]

        profile = peak_hours.compute_peak_hours(rows)

        self.assertAlmostEqual(1200.0, profile[19].average_watts)
        self.assertEqual(4, profile[19].sample_count)
        self.assertEqual(2, profile[19].device_count)

    def test_energy_follows_from_the_load(self):
        profile = peak_hours.compute_peak_hours([reading(1, 3, 1500.0)])

        self.assertAlmostEqual(1.5, profile[3].energy_kwh)
        self.assertAlmostEqual(1.5, peak_hours.daily_energy_kwh(profile))

    def test_ranking_returns_the_busiest_hours_first(self):
        rows = [reading(1, 2, 100.0), reading(1, 19, 900.0), reading(1, 7, 400.0)]

        busiest = peak_hours.rank_peak_hours(peak_hours.compute_peak_hours(rows), top=2)

        self.assertEqual([19, 7], [hour.hour for hour in busiest])

    def test_ranking_never_returns_an_empty_hour(self):
        busiest = peak_hours.rank_peak_hours(
            peak_hours.compute_peak_hours([reading(1, 5, 10.0)]), top=10
        )

        self.assertEqual([5], [hour.hour for hour in busiest])

    def test_no_readings_is_an_empty_profile_not_a_crash(self):
        profile = peak_hours.compute_peak_hours([])

        self.assertEqual(24, len(profile))
        self.assertEqual(0.0, peak_hours.daily_energy_kwh(profile))
        self.assertEqual([], peak_hours.rank_peak_hours(profile))


class DeviceTrendsTest(unittest.TestCase):

    def test_summary_reports_the_basic_shape_of_the_history(self):
        rows = [reading(1, 9, watts) for watts in (100.0, 300.0, 200.0, 400.0)]

        trend = device_trends.summarise(1, rows)

        self.assertEqual(4, trend.sample_count)
        self.assertAlmostEqual(250.0, trend.average_watts)
        self.assertAlmostEqual(100.0, trend.min_watts)
        self.assertAlmostEqual(400.0, trend.max_watts)

    def test_duty_cycle_counts_the_samples_the_appliance_was_running(self):
        # A fridge: 1000 W while the compressor runs, 20 W idle. 20 W is under a fifth of
        # the peak, so those samples are "off".
        rows = [reading(1, 9, watts) for watts in (1000.0, 1000.0, 20.0, 20.0)]

        self.assertAlmostEqual(0.5, device_trends.summarise(1, rows).duty_cycle)

    def test_a_rising_load_is_reported_as_rising(self):
        rows = [reading(1, 9, watts) for watts in (100.0, 100.0, 300.0, 300.0)]

        trend = device_trends.summarise(1, rows)

        self.assertEqual("rising", trend.direction)
        self.assertAlmostEqual(2.0, trend.change_fraction)

    def test_a_falling_load_is_reported_as_falling(self):
        rows = [reading(1, 9, watts) for watts in (400.0, 400.0, 100.0, 100.0)]

        self.assertEqual("falling", device_trends.summarise(1, rows).direction)

    def test_noise_is_not_a_trend(self):
        rows = [reading(1, 9, watts) for watts in (200.0, 202.0, 198.0, 204.0)]

        self.assertEqual("steady", device_trends.summarise(1, rows).direction)

    def test_a_device_with_no_readings_summarises_to_nothing(self):
        trend = device_trends.summarise(6, [])

        self.assertFalse(trend.has_data)
        self.assertEqual(0, trend.sample_count)
        self.assertEqual("steady", trend.direction)


class TariffTest(unittest.TestCase):

    def test_the_default_tariff_prices_every_hour_exactly_once(self):
        self.assertIs(cost_model.DEFAULT_TARIFF, cost_model.DEFAULT_TARIFF.validate())

    def test_a_band_may_wrap_past_midnight(self):
        off_peak = cost_model.TariffBand("off-peak", 22, 6, 4.50)

        self.assertTrue(off_peak.covers(23))
        self.assertTrue(off_peak.covers(2))
        self.assertFalse(off_peak.covers(12))

    def test_rates_come_from_the_band_covering_the_hour(self):
        tariff = cost_model.DEFAULT_TARIFF

        self.assertAlmostEqual(4.50, tariff.rate_for(2))
        self.assertAlmostEqual(6.50, tariff.rate_for(12))
        self.assertAlmostEqual(9.00, tariff.rate_for(19))
        self.assertEqual("peak", tariff.peak_band().name)
        self.assertEqual("off-peak", tariff.cheapest_band().name)

    def test_an_incomplete_tariff_is_refused_rather_than_pricing_hours_at_zero(self):
        gap = cost_model.Tariff("INR", (cost_model.TariffBand("day", 6, 18, 6.5),))

        with self.assertRaises(ValueError):
            gap.validate()

    def test_an_unpriced_hour_raises_rather_than_returning_none(self):
        gap = cost_model.Tariff("INR", (cost_model.TariffBand("day", 6, 18, 6.5),))

        with self.assertRaises(ValueError):
            gap.rate_for(3)


class CostModelTest(unittest.TestCase):

    def test_a_kilowatt_for_an_hour_costs_that_hours_rate(self):
        # 1000 W held through hour 19 is 1 kWh in the peak band, 30 times a month.
        costs = cost_model.estimate_costs([reading(4, 19, 1000.0)])

        self.assertEqual(1, len(costs))
        cost = costs[0]
        self.assertAlmostEqual(1.0, cost.daily_kwh)
        self.assertAlmostEqual(9.00 * 30, cost.monthly_cost)
        self.assertAlmostEqual(1.0, cost.peak_kwh)
        self.assertAlmostEqual(1.0, cost.peak_share)

    def test_shifting_a_peak_load_saves_the_difference_between_the_bands(self):
        costs = cost_model.estimate_costs([reading(4, 19, 1000.0)])

        # 9.00 peak vs 4.50 off-peak, on 1 kWh a day for 30 days.
        self.assertAlmostEqual((9.00 - 4.50) * 30, costs[0].shift_saving_monthly)

    def test_off_peak_energy_has_nothing_to_shift(self):
        costs = cost_model.estimate_costs([reading(4, 2, 1000.0)])

        self.assertAlmostEqual(0.0, costs[0].peak_kwh)
        self.assertAlmostEqual(0.0, costs[0].shift_saving_monthly)

    def test_devices_are_ranked_by_what_they_cost(self):
        rows = [reading(1, 12, 100.0), reading(2, 12, 2000.0), reading(3, 12, 500.0)]

        costs = cost_model.estimate_costs(rows)

        self.assertEqual([2, 3, 1], [cost.device_id for cost in costs])
        self.assertAlmostEqual(
            sum(cost.monthly_cost for cost in costs), cost_model.total_monthly_cost(costs)
        )

    def test_only_deferrable_devices_are_recommended_for_shifting(self):
        rows = [reading(1, 19, 1000.0), reading(4, 19, 1000.0)]
        costs = cost_model.estimate_costs(rows)

        # Device 4 is the water heater; device 1 is the fridge, which cannot be rescheduled.
        recommended = cost_model.recommend_shift(costs, deferrable_ids={4})

        self.assertEqual([4], [cost.device_id for cost in recommended])

    def test_a_saving_too_small_to_act_on_is_not_recommended(self):
        costs = cost_model.estimate_costs([reading(4, 19, 1.0)])

        self.assertEqual([], cost_model.recommend_shift(costs, deferrable_ids={4}))


class ConfigTest(unittest.TestCase):

    def test_defaults_match_the_project_database(self):
        loaded = config.load_db_config(env={})

        self.assertEqual("smart_home_energy", loaded["database"])
        self.assertEqual(3306, loaded["port"])

    def test_the_environment_overrides_the_defaults(self):
        loaded = config.load_db_config(
            env={"ENERGY_DB_HOST": "db.internal", "ENERGY_DB_PORT": "3307"}
        )

        self.assertEqual("db.internal", loaded["host"])
        self.assertEqual(3307, loaded["port"])

    def test_an_empty_override_is_not_an_override(self):
        self.assertEqual("localhost", config.load_db_config(env={"ENERGY_DB_HOST": ""})["host"])

    def test_an_unusable_port_fails_where_it_was_set(self):
        with self.assertRaises(ValueError):
            config.load_db_config(env={"ENERGY_DB_PORT": "three thousand"})

    def test_the_description_never_carries_the_password(self):
        described = config.describe(config.load_db_config(env={"ENERGY_DB_PASSWORD": "hunter2"}))

        self.assertNotIn("hunter2", described)
        self.assertIn("smart_home_energy", described)


class RunnerTest(unittest.TestCase):

    def test_the_pool_is_not_started_for_no_devices(self):
        self.assertEqual([], runner.analyse_devices([]))

    def test_workers_run_in_parallel_and_return_in_order(self):
        # The real analyse_devices against a worker that needs no database: this checks the
        # pool plumbing — that the callable survives being pickled into another process,
        # that the workers run, and that map hands the results back in the order asked for,
        # not the order they finished in.
        device_ids = [3, 1, 2]

        trends = runner.analyse_devices(device_ids, processes=2, worker=analyse_without_a_database)

        self.assertEqual(device_ids, [trend.device_id for trend in trends])
        self.assertTrue(all(trend.has_data for trend in trends))

    def test_tables_render_without_the_optional_dependency(self):
        rendered = runner._plain_table(["id", "device"], [[1, "Kitchen Refrigerator"]])

        self.assertIn("Kitchen Refrigerator", rendered)
        self.assertIn("id", rendered)

    def test_an_empty_table_says_so_rather_than_printing_a_header_alone(self):
        self.assertIn("nothing to report", runner._table(["id"], []))

    def test_arguments_have_the_documented_defaults(self):
        options = runner._parse_args([])

        self.assertIsNone(options.hours)
        self.assertEqual(5, options.top)

        windowed = runner._parse_args(["--hours", "6", "--top", "3", "--processes", "2"])
        self.assertEqual(6, windowed.hours)
        self.assertEqual(3, windowed.top)
        self.assertEqual(2, windowed.processes)


def analyse_without_a_database(device_id, since=None):
    """The worker :class:`RunnerTest` maps: the real summary over invented rows.

    It is a module-level function because it is pickled by name and imported afresh in each
    worker process — a lambda, a closure, or a method would not survive that, which is the
    same constraint the real analysis is written under.
    """
    return device_trends.summarise(
        device_id,
        [reading(device_id, 9, 100.0 * device_id), reading(device_id, 10, 120.0 * device_id)],
    )


if __name__ == "__main__":
    unittest.main()
