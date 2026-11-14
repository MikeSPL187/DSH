"""Compare repeated physical Macrobenchmark reports without accepting mismatched workloads."""
import argparse
import json
import math
from pathlib import Path

REGRESSION_PERCENT = 5.0
TIMINGS = {"timeToInitialDisplayMs", "timeToFullDisplayMs", "frameDurationCpuMs", "frameOverrunMs"}
SETTINGS = ("deviceId", "osBuild", "refreshHz", "fontScale", "powerSave", "compilationMode", "ramGb", "appState")


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def validate_devices(baseline, candidate):
    for record in (baseline, candidate):
        if record.get("isPhysical") is not True:
            raise ValueError("Acceptance requires a physical device")
        if not record.get("sourceRevision"):
            raise ValueError("Source revision is required")
        for key in SETTINGS:
            if key not in record:
                raise ValueError(f"Missing device setting: {key}")
        if record["refreshHz"] not in (60, 120):
            raise ValueError("Measure at fixed 60 or 120 Hz")
        if not isinstance(record["ramGb"], (float, int)) or record["ramGb"] < 8:
            raise ValueError("Reference device requires at least 8 GB RAM")
    if any(baseline[key] != candidate[key] for key in SETTINGS):
        raise ValueError("Device, OS, compilation mode and display/power settings must match")


def metrics(report):
    result = {}
    cases = report.get("benchmarks", [])
    if not cases:
        raise ValueError("Empty benchmark report")
    for case in cases:
        name = case["name"]
        params = json.dumps(case.get("params", {}), sort_keys=True)
        start = len(result)
        for group, statistic in (("metrics", "median"), ("sampledMetrics", "P95")):
            for key, value in case.get(group, {}).items():
                if key == "frameCount":
                    continue  # Counts are diagnostic; fewer rendered frames do not mean faster rendering.
                if key not in TIMINGS:
                    raise ValueError(f"Unclassified metric: {key}")
                if statistic not in value:
                    raise ValueError(f"Missing {statistic} for {name}/{key}")
                number = value[statistic]
                if isinstance(number, bool) or not isinstance(number, (float, int)) or not math.isfinite(number):
                    raise ValueError(f"Invalid value for {name}/{key}")
                identity = f"{name}/{params}/{key}/{statistic}"
                if identity in result:
                    raise ValueError(f"Duplicate benchmark: {identity}")
                result[identity] = number
        if len(result) == start:
            raise ValueError(f"No timing measurements in case: {name}")
    if not result:
        raise ValueError("No comparable metrics")
    return result


def compare(baseline, candidates):
    if len(candidates) < 3:
        raise ValueError("At least three independent candidate reports are required")
    reference = metrics(baseline)
    measured = [metrics(report) for report in candidates]
    if any(set(values) != set(reference) for values in measured):
        raise ValueError("Benchmark cases and metrics must match exactly")
    build = baseline.get("context", {}).get("build")
    if not build or any(report.get("context", {}).get("build") != build for report in candidates):
        raise ValueError("Benchmark-reported device/OS identity must match")
    rows = []
    for key, before in reference.items():
        values = [report[key] for report in measured]
        # Frame overruns may be negative: increasing them still means slower frames.
        limit = before + abs(before) * REGRESSION_PERCENT / 100
        rows.append({"metric": key, "baseline": before, "candidates": values,
                     "limit": limit, "repeatedRegression": all(value > limit for value in values)})
    return {"thresholdPercent": REGRESSION_PERCENT, "accepted": not any(row["repeatedRegression"] for row in rows),
            "metrics": rows, "scope": "relative performance only; not functional or N19 acceptance"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--candidate", nargs="+", required=True)
    parser.add_argument("--baseline-device", required=True)
    parser.add_argument("--candidate-device", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        if len({Path(path).resolve() for path in args.candidate}) != len(args.candidate):
            raise ValueError("Independent reports must have distinct paths")
        baseline_device, candidate_device = load(args.baseline_device), load(args.candidate_device)
        validate_devices(baseline_device, candidate_device)
        report = compare(load(args.baseline), [load(path) for path in args.candidate])
        report["baselineDevice"] = baseline_device
        report["candidateDevice"] = candidate_device
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(2, f"Invalid performance evidence: {error}\n")
    Path(args.output).write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    return 0 if report["accepted"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
