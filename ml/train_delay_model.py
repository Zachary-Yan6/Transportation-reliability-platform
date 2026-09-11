"""Train and evaluate the first offline delay-prediction model.

The Spring Boot application exports labelled CSV data. This script uses that
file offline so model experimentation never interrupts live Kafka ingestion.
It deliberately splits by time, never randomly: a model may learn from the
past but must be evaluated on later observations.
"""

from __future__ import annotations

import argparse
import json
import math
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestRegressor
from sklearn.impute import SimpleImputer
from sklearn.metrics import mean_absolute_error, mean_squared_error
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder


# These are deliberately aligned with the Spring Boot training-data-status
# endpoint. They prevent an accidental claim that a short experiment is a
# reliable production model.
MINIMUM_OBSERVATIONS = 1_000
MINIMUM_COVERAGE_HOURS = 72
MINIMUM_ACTIVE_HOURS = 48
MODEL_VERSION = "SKLEARN_RANDOM_FOREST_V1"
BASELINE_VERSION = "FROZEN_HISTORICAL_AVERAGE_BASELINE_V1"
MINIMUM_TIME_MATCHED_SAMPLES = 5
MINIMUM_STOP_HISTORY_SAMPLES = 3
# A tiny MAE difference can be random variation in a small test set. A model
# must improve by a meaningful margin before it may become a candidate.
MINIMUM_MAE_IMPROVEMENT_SECONDS = 30

# The CSV label is the real NTA-reported delay. All other selected fields are
# known before a prediction is made and are valid model features.
TARGET_COLUMN = "actual_delay_seconds"
CATEGORICAL_FEATURES = [
    "stop_id",
    "observed_day_of_week",
    "observed_hour",
]
NUMERIC_FEATURES = [
    "stop_sequence",
    "scheduled_arrival_seconds",
    "scheduled_departure_seconds",
]
REQUIRED_COLUMNS = {
    "observed_at",
    TARGET_COLUMN,
    *CATEGORICAL_FEATURES,
    *NUMERIC_FEATURES,
}


def parse_arguments() -> argparse.Namespace:
    """Read explicit paths and evaluation options from the command line."""
    parser = argparse.ArgumentParser(
        description="Train a time-safe GTFS delay prediction experiment."
    )
    parser.add_argument(
        "--input",
        required=True,
        type=Path,
        help="CSV exported from /api/v1/ai/routes/{routeId}/training-samples.csv",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("artifacts"),
        help="Directory for the model and JSON evaluation report.",
    )
    parser.add_argument(
        "--test-fraction",
        type=float,
        default=0.20,
        help="Latest fraction of records reserved for testing (default: 0.20).",
    )
    parser.add_argument(
        "--baseline-mae",
        type=float,
        default=None,
        help=(
            "Optional earlier API baseline MAE to retain as a reference. "
            "The report now calculates a fair baseline on the same test set."
        ),
    )
    parser.add_argument(
        "--allow-small-dataset",
        action="store_true",
        help="Run an experimental check before the data-readiness thresholds pass.",
    )
    return parser.parse_args()


def load_and_validate_dataset(input_path: Path) -> pd.DataFrame:
    """Load CSV and convert all model fields into safe, chronological values."""
    if not input_path.is_file():
        raise FileNotFoundError(f"Training CSV was not found: {input_path}")

    frame = pd.read_csv(input_path)
    missing_columns = REQUIRED_COLUMNS.difference(frame.columns)
    if missing_columns:
        joined_columns = ", ".join(sorted(missing_columns))
        raise ValueError(f"The CSV is missing required columns: {joined_columns}")

    # Invalid labels cannot train a supervised model. Timestamp parsing uses
    # UTC so chronological split behaviour is independent of the computer's
    # local timezone.
    frame["observed_at"] = pd.to_datetime(
        frame["observed_at"],
        utc=True,
        errors="coerce",
    )
    frame[TARGET_COLUMN] = pd.to_numeric(
        frame[TARGET_COLUMN],
        errors="coerce",
    )
    frame = frame.dropna(subset=["observed_at", TARGET_COLUMN]).copy()

    # Timetable values can occasionally be absent in a partial GTFS import.
    # SimpleImputer handles those rows later rather than discarding useful
    # real-time delay labels.
    for column in NUMERIC_FEATURES:
        frame[column] = pd.to_numeric(frame[column], errors="coerce")

    # Treat IDs and calendar/time buckets as categories, not as continuous
    # numbers. For example, stop 200 is not inherently twice stop 100.
    for column in CATEGORICAL_FEATURES:
        frame[column] = frame[column].astype("string").fillna("UNKNOWN")

    return frame.sort_values("observed_at").reset_index(drop=True)


def get_data_quality(frame: pd.DataFrame) -> dict[str, float | int | str]:
    """Calculate collection coverage for a transparent training report."""
    earliest = frame["observed_at"].iloc[0]
    latest = frame["observed_at"].iloc[-1]
    coverage_hours = (latest - earliest).total_seconds() / 3600
    active_hours = frame["observed_at"].dt.floor("h").nunique()

    return {
        "observationCount": int(len(frame)),
        "earliestObservedAt": earliest.isoformat(),
        "latestObservedAt": latest.isoformat(),
        "coverageHours": round(coverage_hours, 2),
        "activeHourCount": int(active_hours),
    }


def assert_readiness(
    quality: dict[str, float | int | str], allow_small_dataset: bool
) -> bool:
    """Require adequate collection unless this is an explicitly experimental run."""
    ready = (
        quality["observationCount"] >= MINIMUM_OBSERVATIONS
        and quality["coverageHours"] >= MINIMUM_COVERAGE_HOURS
        and quality["activeHourCount"] >= MINIMUM_ACTIVE_HOURS
    )
    if ready or allow_small_dataset:
        return ready

    raise ValueError(
        "Dataset is not ready for a reliable model. Required: at least "
        f"{MINIMUM_OBSERVATIONS} observations, {MINIMUM_COVERAGE_HOURS} "
        f"coverage hours and {MINIMUM_ACTIVE_HOURS} active hours. Current: "
        f"{quality}. Use --allow-small-dataset only for an engineering test."
    )


def build_pipeline() -> Pipeline:
    """Build preprocessing and the first explainable model candidate."""
    categorical_pipeline = Pipeline(
        steps=[
            ("imputer", SimpleImputer(strategy="most_frequent")),
            ("one_hot", OneHotEncoder(handle_unknown="ignore")),
        ]
    )
    numeric_pipeline = Pipeline(
        steps=[("imputer", SimpleImputer(strategy="median"))]
    )
    preprocessor = ColumnTransformer(
        transformers=[
            ("categorical", categorical_pipeline, CATEGORICAL_FEATURES),
            ("numeric", numeric_pipeline, NUMERIC_FEATURES),
        ]
    )

    # Random Forest is a good first model here: it captures non-linear effects
    # such as a particular stop behaving differently at evening peak time,
    # without requiring a deep-learning stack.
    regressor = RandomForestRegressor(
        n_estimators=300,
        min_samples_leaf=2,
        random_state=42,
        n_jobs=-1,
    )
    return Pipeline(
        steps=[("preprocess", preprocessor), ("regressor", regressor)]
    )


def chronological_split(
    frame: pd.DataFrame, test_fraction: float
) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Reserve the newest records as the test set, with no random shuffle."""
    if not 0 < test_fraction < 0.5:
        raise ValueError("--test-fraction must be greater than 0 and below 0.5")

    test_count = max(1, math.ceil(len(frame) * test_fraction))
    train_count = len(frame) - test_count
    if train_count < 10:
        raise ValueError("At least 11 valid observations are needed to train.")

    return frame.iloc[:train_count], frame.iloc[train_count:]


def calculate_metrics(
    actual: pd.Series, predicted: np.ndarray
) -> dict[str, float]:
    """Calculate the three metrics used throughout this project."""
    actual_values = actual.to_numpy(dtype=float)
    predicted_values = np.asarray(predicted, dtype=float)

    return {
        "meanAbsoluteErrorSeconds": round(
            float(mean_absolute_error(actual_values, predicted_values)),
            2,
        ),
        "rootMeanSquaredErrorSeconds": round(
            float(math.sqrt(mean_squared_error(actual_values, predicted_values))),
            2,
        ),
        "withinFiveMinutesPercentage": round(
            float((np.abs(actual_values - predicted_values) <= 300).mean() * 100),
            2,
        ),
    }


def predict_frozen_historical_baseline(
    train_frame: pd.DataFrame, test_frame: pd.DataFrame
) -> tuple[np.ndarray, dict[str, int]]:
    """Predict the test rows using only data available in the training period.

    This mirrors the Java baseline hierarchy but deliberately freezes it at
    the train/test boundary. Consequently both the Random Forest and the
    baseline receive exactly the same historical information.
    """
    route_average = float(train_frame[TARGET_COLUMN].mean())
    stop_statistics = train_frame.groupby("stop_id")[TARGET_COLUMN].agg(
        ["count", "mean"]
    )
    time_statistics = train_frame.groupby(
        ["stop_id", "observed_day_of_week", "observed_hour"]
    )[TARGET_COLUMN].agg(["count", "mean"])

    predictions = np.empty(len(test_frame), dtype=float)
    source_counts = {
        "sameWeekdayHour": 0,
        "stopHistory": 0,
        "routeFallback": 0,
    }

    for index, values in enumerate(
        test_frame[
            ["stop_id", "observed_day_of_week", "observed_hour"]
        ].itertuples(index=False, name=None)
    ):
        stop_id, day_of_week, hour = values
        time_key = (stop_id, day_of_week, hour)

        if (
            time_key in time_statistics.index
            and time_statistics.loc[time_key, "count"]
            >= MINIMUM_TIME_MATCHED_SAMPLES
        ):
            predictions[index] = time_statistics.loc[time_key, "mean"]
            source_counts["sameWeekdayHour"] += 1
        elif (
            stop_id in stop_statistics.index
            and stop_statistics.loc[stop_id, "count"]
            >= MINIMUM_STOP_HISTORY_SAMPLES
        ):
            predictions[index] = stop_statistics.loc[stop_id, "mean"]
            source_counts["stopHistory"] += 1
        else:
            predictions[index] = route_average
            source_counts["routeFallback"] += 1

    return predictions, source_counts


def determine_promotion_decision(
    readiness_passed: bool,
    model_metrics: dict[str, float],
    baseline_metrics: dict[str, float],
) -> dict[str, object]:
    """Decide whether a model is safe to consider for later API deployment.

    This does not deploy anything. It records an evidence-based decision that
    prevents an experimental or barely better model from replacing the stable
    Spring Boot historical baseline.
    """
    reasons: list[str] = []
    mae_improvement = round(
        baseline_metrics["meanAbsoluteErrorSeconds"]
        - model_metrics["meanAbsoluteErrorSeconds"],
        2,
    )

    if not readiness_passed:
        reasons.append(
            "Training-data readiness thresholds have not been reached."
        )
    if mae_improvement < MINIMUM_MAE_IMPROVEMENT_SECONDS:
        reasons.append(
            "The model did not improve MAE by the required "
            f"{MINIMUM_MAE_IMPROVEMENT_SECONDS} seconds."
        )
    if (
        model_metrics["withinFiveMinutesPercentage"]
        < baseline_metrics["withinFiveMinutesPercentage"]
    ):
        reasons.append(
            "The model has a lower five-minute accuracy than the baseline."
        )

    eligible = not reasons
    return {
        "status": "PROMOTE_CANDIDATE" if eligible else "KEEP_BASELINE",
        "eligibleForManualPromotion": eligible,
        "minimumMaeImprovementSeconds": MINIMUM_MAE_IMPROVEMENT_SECONDS,
        "maeImprovementSeconds": mae_improvement,
        "reasons": reasons,
    }


def build_report(
    quality: dict[str, float | int | str],
    train_frame: pd.DataFrame,
    test_frame: pd.DataFrame,
    actual: pd.Series,
    model_predicted: np.ndarray,
    baseline_predicted: np.ndarray,
    baseline_source_counts: dict[str, int],
    earlier_api_baseline_mae: float | None,
    readiness_passed: bool,
) -> dict[str, object]:
    """Create a report with a like-for-like model/baseline comparison."""
    model_metrics = calculate_metrics(actual, model_predicted)
    baseline_metrics = calculate_metrics(actual, baseline_predicted)
    model_mae = model_metrics["meanAbsoluteErrorSeconds"]
    baseline_mae = baseline_metrics["meanAbsoluteErrorSeconds"]
    promotion_decision = determine_promotion_decision(
        readiness_passed,
        model_metrics,
        baseline_metrics,
    )

    report: dict[str, object] = {
        "modelVersion": MODEL_VERSION,
        "runAtUtc": datetime.now(timezone.utc).isoformat(),
        "status": "READY_FOR_COMPARISON" if readiness_passed else "EXPERIMENTAL",
        "dataQuality": quality,
        "split": {
            "strategy": "chronological",
            "trainingRowCount": int(len(train_frame)),
            "testRowCount": int(len(test_frame)),
            "trainingEndsAt": train_frame["observed_at"].iloc[-1].isoformat(),
            "testingStartsAt": test_frame["observed_at"].iloc[0].isoformat(),
        },
        # Kept as the model's metrics so existing consumers of this report
        # remain valid. The baseline appears separately below.
        "testMetrics": model_metrics,
        "frozenHistoricalBaseline": {
            "version": BASELINE_VERSION,
            "metrics": baseline_metrics,
            "predictionSourceCounts": baseline_source_counts,
            "note": (
                "The baseline used training-period observations only, on "
                "the same test rows as the model."
            ),
        },
        "sameTestSetComparison": {
            "modelBeatsBaseline": model_mae < baseline_mae,
            "maeImprovementSeconds": round(baseline_mae - model_mae, 2),
            "modelMeanAbsoluteErrorSeconds": model_mae,
            "baselineMeanAbsoluteErrorSeconds": baseline_mae,
        },
        "promotionDecision": promotion_decision,
        "features": CATEGORICAL_FEATURES + NUMERIC_FEATURES,
        "target": TARGET_COLUMN,
    }

    if earlier_api_baseline_mae is not None:
        report["earlierApiBaselineReference"] = {
            "meanAbsoluteErrorSeconds": round(earlier_api_baseline_mae, 2),
            "note": (
                "This earlier API result may use a different time window; "
                "use sameTestSetComparison for the fair decision."
            ),
        }

    return report


def main() -> None:
    """Train the model, save it, and print an auditable evaluation summary."""
    arguments = parse_arguments()
    frame = load_and_validate_dataset(arguments.input)
    if frame.empty:
        raise ValueError("The CSV has no valid labelled observations.")

    quality = get_data_quality(frame)
    readiness_passed = assert_readiness(
        quality,
        arguments.allow_small_dataset,
    )
    train_frame, test_frame = chronological_split(
        frame,
        arguments.test_fraction,
    )

    feature_columns = CATEGORICAL_FEATURES + NUMERIC_FEATURES
    model = build_pipeline()
    model.fit(train_frame[feature_columns], train_frame[TARGET_COLUMN])
    model_predicted = model.predict(test_frame[feature_columns])
    baseline_predicted, baseline_source_counts = (
        predict_frozen_historical_baseline(train_frame, test_frame)
    )

    report = build_report(
        quality,
        train_frame,
        test_frame,
        test_frame[TARGET_COLUMN],
        model_predicted,
        baseline_predicted,
        baseline_source_counts,
        arguments.baseline_mae,
        readiness_passed,
    )

    arguments.output_dir.mkdir(parents=True, exist_ok=True)
    joblib.dump(model, arguments.output_dir / "delay_model.joblib")
    (arguments.output_dir / "training_report.json").write_text(
        json.dumps(report, indent=2),
        encoding="utf-8",
    )

    print(json.dumps(report, indent=2))
    print(f"\nSaved model: {arguments.output_dir / 'delay_model.joblib'}")
    print(f"Saved report: {arguments.output_dir / 'training_report.json'}")


if __name__ == "__main__":
    main()
