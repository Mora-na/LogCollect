#!/usr/bin/env python3
"""
Compare two JMH JSON result files.

Usage:
  python3 compare-baseline.py baseline.json current.json [threshold_pct]
"""

import json
import sys


def load_results(path):
    with open(path, "r", encoding="utf-8") as file:
        data = json.load(file)

    results = {}
    for item in data:
        name = item["benchmark"].split(".")[-1]
        metric = item.get("primaryMetric", {})
        score = float(metric.get("score", 0.0))
        error = float(metric.get("scoreError", 0.0))
        results[name] = {"score": score, "error": error}
    return results


def compare(baseline_path, current_path, threshold_pct=15):
    baseline = load_results(baseline_path)
    current = load_results(current_path)

    print("\n{:<50} {:>12} {:>12} {:>10} {:>10}".format(
        "Benchmark", "Baseline", "Current", "Change", "Status"
    ))
    print("=" * 100)

    all_pass = True
    all_names = sorted(set(list(baseline.keys()) + list(current.keys())))

    for name in all_names:
        if name not in baseline:
            print("{:<50} {:>12} {:>12.1f} {:>10} {:>10}".format(name, "N/A", current[name]["score"], "NEW", "INFO"))
            continue
        if name not in current:
            print("{:<50} {:>12.1f} {:>12} {:>10} {:>10}".format(name, baseline[name]["score"], "N/A", "REMOVED", "INFO"))
            continue

        b = baseline[name]["score"]
        c = current[name]["score"]
        if b == 0:
            continue

        change_pct = ((c - b) / b) * 100.0
        status = "OK"
        if change_pct > threshold_pct:
            status = "SLOWER"
            all_pass = False
        elif change_pct < -threshold_pct:
            status = "FASTER"

        print("{:<50} {:>12.1f} {:>12.1f} {:>+9.1f}% {:>10}".format(name, b, c, change_pct, status))

    print("=" * 100)
    if all_pass:
        print("\nAll benchmarks are within threshold")
        return 0

    print("\nSome benchmarks degraded more than {}%".format(threshold_pct))
    return 1


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: {} baseline.json current.json [threshold_pct]".format(sys.argv[0]))
        sys.exit(1)

    threshold = int(sys.argv[3]) if len(sys.argv) > 3 else 15
    sys.exit(compare(sys.argv[1], sys.argv[2], threshold))
