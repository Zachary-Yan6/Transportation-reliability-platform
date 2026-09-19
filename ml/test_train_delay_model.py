"""Focused regression tests for delay-model data-quality and time-split rules."""

from __future__ import annotations

import unittest

import pandas as pd

from train_delay_model import (
    MINIMUM_STOP_OBSERVATIONS,
    calendar_week_time_split,
    get_stop_training_quality,
)


class DelayModelDataSafetyTest(unittest.TestCase):
    """Exercise the rules that prevent sparse data and temporal leakage."""

    def test_calendar_split_keeps_the_future_weekend_out_of_training(self) -> None:
        frame = pd.DataFrame(
            {
                "observed_at": pd.date_range(
                    "2026-01-05T00:00:00Z",
                    periods=14 * 24,
                    freq="h",
                )
            }
        )

        train_frame, test_frame = calendar_week_time_split(frame, 0.20)

        self.assertLess(
            train_frame["observed_at"].max(),
            test_frame["observed_at"].min(),
        )
        first_test_day = test_frame["observed_at"].iloc[0].tz_convert(
            "Europe/Dublin"
        )
        self.assertEqual(5, first_test_day.weekday())
        self.assertEqual(0, first_test_day.hour)

    def test_stop_is_eligible_only_above_both_strict_thresholds(self) -> None:
        start = pd.Timestamp("2026-01-01T00:00:00Z")
        # Repeated observations are enough for this boundary test: only their
        # count and the earliest/latest timestamps matter to the gate.
        qualifying_timestamps = pd.Series(
            [start] * (MINIMUM_STOP_OBSERVATIONS - 1)
            + [start + pd.Timedelta(days=7, seconds=1)]
        )
        too_few_timestamps = pd.Series(
            [start] * (MINIMUM_STOP_OBSERVATIONS - 2)
            + [start + pd.Timedelta(days=8)]
        )
        exactly_seven_day_timestamps = pd.Series(
            [start] * (MINIMUM_STOP_OBSERVATIONS - 1)
            + [start + pd.Timedelta(days=7)]
        )

        frame = pd.DataFrame(
            {
                "stop_id": (
                    ["eligible"] * MINIMUM_STOP_OBSERVATIONS
                    + ["too_few"] * (MINIMUM_STOP_OBSERVATIONS - 1)
                    + ["exactly_seven_days"] * MINIMUM_STOP_OBSERVATIONS
                ),
                "observed_at": pd.concat(
                    [
                        qualifying_timestamps,
                        too_few_timestamps,
                        exactly_seven_day_timestamps,
                    ],
                    ignore_index=True,
                ),
            }
        )

        quality = get_stop_training_quality(frame)

        self.assertTrue(quality["eligible"]["eligible"])
        self.assertFalse(quality["too_few"]["eligible"])
        self.assertFalse(quality["exactly_seven_days"]["eligible"])


if __name__ == "__main__":
    unittest.main()
