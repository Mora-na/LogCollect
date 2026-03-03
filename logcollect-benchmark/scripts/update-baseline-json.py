#!/usr/bin/env python3
"""
Update structured benchmark baseline JSON from JMH JSON output.

Usage:
  python3 update-baseline-json.py <jmh_result.json> <baseline.json>
"""

import json
import os
import platform
import sys
from datetime import datetime, timezone


def compute_default_multiplier(score_ns):
    """Pick a conservative CI ceiling multiplier by score magnitude."""
    if score_ns < 100:
        return 100.0
    if score_ns < 1000:
        return 25.0
    if score_ns < 10000:
        return 10.0
    return 5.0


def main():
    if len(sys.argv) < 3:
        print("Usage: {} <jmh_result.json> <baseline.json>".format(sys.argv[0]))
        sys.exit(1)

    jmh_path = sys.argv[1]
    baseline_path = sys.argv[2]

    with open(jmh_path, "r", encoding="utf-8") as file:
        jmh_data = json.load(file)

    existing = {}
    if os.path.exists(baseline_path):
        with open(baseline_path, "r", encoding="utf-8") as file:
            existing = json.load(file)

    existing_baselines = existing.get("baselines", {})
    existing_ratios = existing.get("ratios", {})

    new_baselines = {}
    for item in jmh_data:
        name = item["benchmark"].split(".")[-1]
        primary = item.get("primaryMetric", {})
        score = float(primary.get("score", 0.0))
        error = float(primary.get("scoreError", 0.0))

        old = existing_baselines.get(name, {})
        multiplier = old.get("ciCeilingMultiplier", compute_default_multiplier(score))

        new_baselines[name] = {
            "scoreNs": round(score, 1),
            "errorNs": round(error, 1),
            "ciCeilingMultiplier": multiplier,
        }

    java_ver = platform.java_ver()[0]
    meta = {
        "updatedAt": datetime.now(timezone.utc).isoformat(),
        "updatedBy": os.environ.get("USER", "unknown"),
        "jdk": java_ver if java_ver else "unknown",
        "os": "{} {}".format(platform.system(), platform.release()),
        "cpu": platform.processor() or "unknown",
        "note": "Auto-updated by update-baseline.sh",
    }

    result = {
        "_meta": meta,
        "baselines": new_baselines,
        "ratios": existing_ratios,
    }

    with open(baseline_path, "w", encoding="utf-8") as file:
        json.dump(result, file, indent=2, ensure_ascii=False)
        file.write("\n")

    print("Updated {} baselines in {}".format(len(new_baselines), baseline_path))
    for name in sorted(new_baselines):
        data = new_baselines[name]
        ceiling = data["scoreNs"] * data["ciCeilingMultiplier"]
        print("  {}: {:.1f} +- {:.1f} ns (ceiling: {:.0f} ns)".format(
            name,
            data["scoreNs"],
            data["errorNs"],
            ceiling,
        ))


if __name__ == "__main__":
    main()
