#!/usr/bin/env python3
"""
Update structured benchmark baseline JSON from JMH JSON output.

Usage:
  python3 update-baseline-json.py <jmh_result.json> <baseline.json> [options]
"""

import argparse
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


def parse_args():
    parser = argparse.ArgumentParser(
        description="Update structured benchmark baseline JSON from JMH output."
    )
    parser.add_argument("jmh_result_json")
    parser.add_argument("baseline_json")
    parser.add_argument("--jdk-key", default=None, help="Target key in jdkBaselines, e.g. jdk17")
    parser.add_argument("--default-jdk-key", default="jdk17", help="Canonical JDK key for legacy top-level baselines")
    parser.add_argument("--jdk-version", default=None, help="Human-readable JDK version string")
    parser.add_argument("--java-home", default=None, help="JAVA_HOME used for this run")
    parser.add_argument("--jvm-args", default="", help="JVM args used during JMH run")
    parser.add_argument("--jmh-args", default="", help="JMH args used during run")
    parser.add_argument("--updated-by", default=None, help="Operator name")
    parser.add_argument("--note", default="Auto-updated by update-baseline-json.py", help="Meta note")
    return parser.parse_args()


def parse_major(java_ver):
    if not java_ver:
        return None
    raw = java_ver.strip()
    if not raw:
        return None
    if raw.startswith("1."):
        suffix = raw[2:]
        parts = suffix.split(".")
    else:
        parts = raw.split(".")
    try:
        return int(parts[0])
    except Exception:
        return None


def infer_jdk_key(args, java_version):
    if args.jdk_key:
        return args.jdk_key
    major = parse_major(java_version)
    if major is None:
        return "unknown"
    return "jdk{}".format(major)


def iso_now_utc():
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def read_json_if_exists(path):
    if not os.path.exists(path):
        return {}
    with open(path, "r", encoding="utf-8") as file:
        return json.load(file)


def jdk_entry_baselines(existing, jdk_key):
    jdk_root = existing.get("jdkBaselines", {})
    if not isinstance(jdk_root, dict):
        return {}
    entry = jdk_root.get(jdk_key, {})
    if not isinstance(entry, dict):
        return {}
    baselines = entry.get("baselines", {})
    return baselines if isinstance(baselines, dict) else {}


def main():
    args = parse_args()
    jmh_path = args.jmh_result_json
    baseline_path = args.baseline_json

    with open(jmh_path, "r", encoding="utf-8") as file:
        jmh_data = json.load(file)

    existing = read_json_if_exists(baseline_path)
    existing_legacy_baselines = existing.get("baselines", {})
    if not isinstance(existing_legacy_baselines, dict):
        existing_legacy_baselines = {}
    existing_ratios = existing.get("ratios", {})
    if not isinstance(existing_ratios, dict):
        existing_ratios = {}

    java_ver = args.jdk_version or platform.java_ver()[0]
    jdk_key = infer_jdk_key(args, java_ver)
    existing_jdk_baselines = jdk_entry_baselines(existing, jdk_key)

    new_baselines = {}
    for item in jmh_data:
        name = item["benchmark"].split(".")[-1]
        primary = item.get("primaryMetric", {})
        score = float(primary.get("score", 0.0))
        error = float(primary.get("scoreError", 0.0))

        old = existing_jdk_baselines.get(name, {})
        if not isinstance(old, dict):
            old = {}
        if "ciCeilingMultiplier" not in old:
            old = existing_legacy_baselines.get(name, {})
            if not isinstance(old, dict):
                old = {}
        multiplier = old.get("ciCeilingMultiplier", compute_default_multiplier(score))

        new_baselines[name] = {
            "scoreNs": round(score, 1),
            "errorNs": round(error, 1),
            "ciCeilingMultiplier": multiplier,
        }

    now = iso_now_utc()
    jdk_meta = {
        "updatedAt": now,
        "updatedBy": args.updated_by or os.environ.get("USER", "unknown"),
        "jdk": java_ver if java_ver else "unknown",
        "jdkKey": jdk_key,
        "javaHome": args.java_home or os.environ.get("JAVA_HOME", "unknown"),
        "os": "{} {}".format(platform.system(), platform.release()),
        "cpu": platform.machine() or platform.processor() or "unknown",
        "jvmArgs": args.jvm_args,
        "jmhArgs": args.jmh_args,
        "note": args.note,
    }

    jdk_root = existing.get("jdkBaselines", {})
    if not isinstance(jdk_root, dict):
        jdk_root = {}
    old_entry = jdk_root.get(jdk_key, {})
    old_entry_ratios = {}
    if isinstance(old_entry, dict):
        ratios_candidate = old_entry.get("ratios", {})
        if isinstance(ratios_candidate, dict):
            old_entry_ratios = ratios_candidate
    jdk_root[jdk_key] = {
        "_meta": jdk_meta,
        "baselines": new_baselines,
        "ratios": old_entry_ratios,
    }

    legacy_meta = existing.get("_meta", {})
    if not isinstance(legacy_meta, dict):
        legacy_meta = {}
    legacy_meta.update({
        "updatedAt": now,
        "updatedBy": jdk_meta["updatedBy"],
        "defaultJdkKey": args.default_jdk_key,
        "note": "Degradation baseline for CI smoke gate (auto-selected by runtime JDK)",
    })

    legacy_baselines = existing_legacy_baselines
    if jdk_key == args.default_jdk_key or not legacy_baselines:
        legacy_baselines = new_baselines
        legacy_meta.update({
            "jdk": jdk_meta["jdk"],
            "jdkKey": jdk_meta["jdkKey"],
            "javaHome": jdk_meta["javaHome"],
            "os": jdk_meta["os"],
            "cpu": jdk_meta["cpu"],
            "jvmArgs": jdk_meta["jvmArgs"],
            "jmhArgs": jdk_meta["jmhArgs"],
        })

    result = {
        "_meta": legacy_meta,
        "baselines": legacy_baselines,
        "jdkBaselines": jdk_root,
        "ratios": existing_ratios,
    }

    with open(baseline_path, "w", encoding="utf-8") as file:
        json.dump(result, file, indent=2, ensure_ascii=False)
        file.write("\n")

    print("Updated {} baselines for {} in {}".format(len(new_baselines), jdk_key, baseline_path))
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
