"""Run one promoted scikit-learn delay model prediction for Spring Boot.

The Java application owns source selection and fallbacks. This script only
loads one joblib artifact, rebuilds the feature row used during training, and
prints a small JSON result that Java can validate.
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path

import joblib
import pandas as pd


FEATURE_COLUMNS = [
    "stop_id",
    "observed_day_of_week",
    "observed_hour",
    "stop_sequence",
    "scheduled_arrival_seconds",
    "scheduled_departure_seconds",
]


def parse_arguments() -> argparse.Namespace:
    """Read the scheduled-stop features supplied by the Java application."""
    parser = argparse.ArgumentParser(
        description="Predict one GTFS stop delay with a saved joblib model."
    )
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--stop-id", required=True)
    parser.add_argument("--target-time", required=True)
    parser.add_argument("--stop-sequence", required=True, type=int)
    parser.add_argument("--scheduled-arrival-seconds", type=int)
    parser.add_argument("--scheduled-departure-seconds", type=int)
    return parser.parse_args()


def main() -> None:
    """Load the artifact and emit exactly one JSON response on standard output."""
    arguments = parse_arguments()
    if not arguments.model.is_file():
        raise FileNotFoundError(f"Model was not found: {arguments.model}")

    # The training exporter computes weekday and hour in Dublin time. Convert
    # the incoming UTC instant in exactly the same way to avoid feature drift.
    target_time = pd.to_datetime(arguments.target_time, utc=True).tz_convert(
        "Europe/Dublin"
    )
    row = pd.DataFrame(
        [
            {
                "stop_id": str(arguments.stop_id),
                "observed_day_of_week": str(target_time.isoweekday()),
                "observed_hour": str(target_time.hour),
                "stop_sequence": arguments.stop_sequence,
                "scheduled_arrival_seconds": arguments.scheduled_arrival_seconds,
                "scheduled_departure_seconds": arguments.scheduled_departure_seconds,
            }
        ]
    )

    model = joblib.load(arguments.model)
    prediction = float(model.predict(row[FEATURE_COLUMNS])[0])
    print(json.dumps({"predictedDelaySeconds": round(prediction, 2)}))


if __name__ == "__main__":
    main()
