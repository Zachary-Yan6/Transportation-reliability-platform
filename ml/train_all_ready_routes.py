"""Train and safely deploy delay models for every route ready for training.

The script asks Spring Boot which active-feed routes pass the same data-quality
gates shown by the dashboard. It pages through each CSV export, trains outside
the live application process, and deploys an artifact only when the report
approves model promotion. A rejected candidate never replaces an older
promoted model.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import shutil
import subprocess
import sys
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlencode
from urllib.request import urlopen


DEFAULT_API_BASE_URL = "http://localhost:8080/api/v1"
MAXIMUM_PAGE_SIZE = 10_000
EXPECTED_COLUMNS = {
    "actual_delay_seconds",
    "observed_at",
    "observed_day_of_week",
    "observed_hour",
    "scheduled_arrival_seconds",
    "scheduled_departure_seconds",
    "stop_id",
    "stop_sequence",
}


@dataclass
class RouteTrainingResult:
    """One route's auditable result in the batch summary JSON."""

    route_id: int
    route_short_name: str | None
    downloaded_rows: int = 0
    status: str = "PENDING"
    model_deployed: bool = False
    report_path: str | None = None
    message: str | None = None


def parse_arguments() -> argparse.Namespace:
    """Read explicit locations so batch work never depends on an IDE setup."""
    parser = argparse.ArgumentParser(
        description="Train promoted GTFS delay models for all ready routes."
    )
    parser.add_argument("--api-base-url", default=DEFAULT_API_BASE_URL)
    parser.add_argument(
        "--artifacts-directory",
        type=Path,
        default=Path("ml/artifacts"),
    )
    parser.add_argument(
        "--working-directory",
        type=Path,
        default=Path("ml/.training-data"),
    )
    parser.add_argument(
        "--page-size",
        type=int,
        default=MAXIMUM_PAGE_SIZE,
        help="CSV rows per HTTP request; maximum is 10,000.",
    )
    parser.add_argument(
        "--python-command",
        default=sys.executable,
        help="Python interpreter used to invoke train_delay_model.py.",
    )
    parser.add_argument(
        "--keep-csv",
        action="store_true",
        help="Keep downloaded CSV files after a successful route run.",
    )
    return parser.parse_args()


def get_json(url: str) -> Any:
    """Fetch JSON using the standard library to avoid another dependency."""
    with urlopen(url, timeout=30) as response:  # noqa: S310 - URL is explicit CLI input.
        return json.loads(response.read().decode("utf-8"))


def get_ready_routes(api_base_url: str) -> list[dict[str, Any]]:
    """Read the backend's authoritative active-feed training candidates."""
    response = get_json(f"{api_base_url.rstrip('/')}/ai/routes/ready-for-training")
    if not isinstance(response, list):
        raise ValueError("Ready-routes endpoint returned a non-array response.")
    return response


def fetch_csv_page(
    api_base_url: str,
    route_id: int,
    limit: int,
    offset: int,
) -> str:
    """Download one bounded page of chronological labelled samples."""
    query = urlencode({"limit": limit, "offset": offset})
    url = (
        f"{api_base_url.rstrip('/')}/ai/routes/{route_id}/"
        f"training-samples.csv?{query}"
    )
    with urlopen(url, timeout=60) as response:  # noqa: S310 - URL is explicit CLI input.
        return response.read().decode("utf-8")


def download_route_dataset(
    api_base_url: str,
    route_id: int,
    destination: Path,
    page_size: int,
) -> int:
    """Join CSV pages into one complete, validated training file."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    offset = 0
    total_rows = 0
    expected_header: list[str] | None = None

    with destination.open("w", newline="", encoding="utf-8") as output_file:
        writer: csv.DictWriter | None = None

        while True:
            page = fetch_csv_page(api_base_url, route_id, page_size, offset)
            reader = csv.DictReader(io.StringIO(page))
            if reader.fieldnames is None:
                raise ValueError(f"Route {route_id} CSV did not contain a header.")

            if expected_header is None:
                expected_header = reader.fieldnames
                missing_columns = EXPECTED_COLUMNS.difference(expected_header)
                if missing_columns:
                    joined = ", ".join(sorted(missing_columns))
                    raise ValueError(
                        f"Route {route_id} CSV is missing required columns: {joined}"
                    )
                writer = csv.DictWriter(output_file, fieldnames=expected_header)
                writer.writeheader()
            elif reader.fieldnames != expected_header:
                raise ValueError(f"Route {route_id} CSV header changed during download.")

            rows_in_page = 0
            for row in reader:
                # DictReader and writer retain CSV escaping correctly even when
                # a GTFS external identifier contains commas or line breaks.
                writer.writerow(row)
                rows_in_page += 1

            total_rows += rows_in_page
            if rows_in_page < page_size:
                return total_rows
            offset += rows_in_page


def train_candidate(
    python_command: str,
    csv_path: Path,
    candidate_directory: Path,
) -> subprocess.CompletedProcess[str]:
    """Run the existing chronological trainer in an isolated candidate folder."""
    trainer = Path(__file__).with_name("train_delay_model.py")
    candidate_directory.mkdir(parents=True, exist_ok=True)
    return subprocess.run(
        [
            python_command,
            str(trainer),
            "--input",
            str(csv_path),
            "--output-dir",
            str(candidate_directory),
        ],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )


def deploy_promoted_candidate(candidate_directory: Path, target_directory: Path) -> None:
    """Replace the two production files only after both candidate files exist."""
    model = candidate_directory / "delay_model.joblib"
    report = candidate_directory / "training_report.json"
    if not model.is_file() or not report.is_file():
        raise FileNotFoundError("Candidate training output is incomplete.")

    target_directory.mkdir(parents=True, exist_ok=True)
    for source in (model, report):
        temporary_target = target_directory / f".{source.name}.new"
        shutil.copy2(source, temporary_target)
        temporary_target.replace(target_directory / source.name)


def write_summary(
    artifacts_directory: Path,
    results: list[RouteTrainingResult],
) -> Path:
    """Persist a batch manifest so model deployment decisions are traceable."""
    artifacts_directory.mkdir(parents=True, exist_ok=True)
    summary_path = artifacts_directory / "batch-training-summary.json"
    payload = {
        "runAtUtc": datetime.now(timezone.utc).isoformat(),
        "readyRouteCount": len(results),
        "deployedModelCount": sum(result.model_deployed for result in results),
        "results": [asdict(result) for result in results],
    }
    summary_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return summary_path


def run_for_route(
    route: dict[str, Any],
    arguments: argparse.Namespace,
) -> RouteTrainingResult:
    """Download, evaluate, and conditionally deploy one route's candidate."""
    route_id = int(route["routeId"])
    result = RouteTrainingResult(
        route_id=route_id,
        route_short_name=route.get("routeShortName"),
    )
    csv_path = arguments.working_directory / f"route-{route_id}.csv"
    candidate_directory = arguments.artifacts_directory / ".candidates" / f"route-{route_id}"

    try:
        if candidate_directory.exists():
            shutil.rmtree(candidate_directory)

        result.downloaded_rows = download_route_dataset(
            arguments.api_base_url,
            route_id,
            csv_path,
            arguments.page_size,
        )
        if result.downloaded_rows == 0:
            result.status = "SKIPPED_NO_LABELLED_SAMPLES"
            result.message = "The route passed readiness but exported no joinable samples."
            return result

        process = train_candidate(
            arguments.python_command,
            csv_path,
            candidate_directory,
        )
        (candidate_directory / "training.log").write_text(
            process.stdout + "\n--- STDERR ---\n" + process.stderr,
            encoding="utf-8",
        )
        if process.returncode != 0:
            result.status = "TRAINING_FAILED"
            result.message = process.stderr.strip() or process.stdout.strip()
            return result

        report_path = candidate_directory / "training_report.json"
        report = json.loads(report_path.read_text(encoding="utf-8"))
        result.report_path = str(report_path)
        promoted = bool(
            report.get("promotionDecision", {}).get("eligibleForManualPromotion")
        )
        if not promoted:
            result.status = "KEPT_BASELINE"
            result.message = (
                "Candidate completed but did not beat the historical baseline; "
                "no production model was replaced."
            )
            return result

        deploy_promoted_candidate(
            candidate_directory,
            arguments.artifacts_directory / f"route-{route_id}",
        )
        result.status = "DEPLOYED"
        result.model_deployed = True
        result.report_path = str(
            arguments.artifacts_directory
            / f"route-{route_id}"
            / "training_report.json"
        )
        result.message = "Candidate beat the baseline and was deployed."
        return result
    except Exception as exception:  # Keep another route's failure isolated.
        result.status = "FAILED"
        result.message = str(exception)
        return result
    finally:
        if not arguments.keep_csv:
            csv_path.unlink(missing_ok=True)


def main() -> None:
    """Train every currently-ready route and return non-zero on technical failures."""
    arguments = parse_arguments()
    if not 1 <= arguments.page_size <= MAXIMUM_PAGE_SIZE:
        raise ValueError(f"--page-size must be between 1 and {MAXIMUM_PAGE_SIZE}.")

    routes = get_ready_routes(arguments.api_base_url)
    if not routes:
        print("No active-feed routes currently meet all training-data quality gates.")
        return

    results = [run_for_route(route, arguments) for route in routes]
    summary_path = write_summary(arguments.artifacts_directory, results)
    for result in results:
        print(
            f"Route {result.route_id} ({result.route_short_name}): "
            f"{result.status}; rows={result.downloaded_rows}; {result.message}"
        )
    print(f"Saved batch summary: {summary_path}")

    if any(result.status in {"FAILED", "TRAINING_FAILED"} for result in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
