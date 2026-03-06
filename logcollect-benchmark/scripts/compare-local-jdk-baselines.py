#!/usr/bin/env python3
"""
Render cross-JDK baseline comparison table from benchmark-baseline.json.

Usage:
  python3 compare-local-jdk-baselines.py [baseline_json]
"""

import json
import sys
from pathlib import Path


BENCH_ORDER = [
    "interface_noop",
    "interface_virtualDispatch",
    "reflection_getMethods_everyTime",
    "fullCopy_typical",
    "fullCopy_large",
    "lazyCopy_typical_clean",
    "lazyCopy_large_clean",
    "sanitize_cleanMessage",
    "sanitize_withInjection",
    "sanitize_longMessage",
    "pipeline_cleanMessage_emptyMdc",
    "pipeline_sensitiveMessage_typicalMdc",
    "pipeline_perCall_newInstance",
    "pipeline_withThrowable",
]


def fmt_score(value):
    if value is None:
        return "-"
    return f"{value:.1f}"


def ordered_jdk_keys(jdk_baselines):
    preferred = ["jdk8", "jdk11", "jdk17", "jdk21"]
    result = [key for key in preferred if key in jdk_baselines]
    for key in sorted(jdk_baselines.keys()):
        if key not in result:
            result.append(key)
    return result


def extract_benchmarks(jdk_baselines, jdk_keys):
    names = set()
    for key in jdk_keys:
        entry = jdk_baselines.get(key, {})
        baselines = entry.get("baselines", {}) if isinstance(entry, dict) else {}
        if isinstance(baselines, dict):
            names.update(baselines.keys())

    ordered = [name for name in BENCH_ORDER if name in names]
    for name in sorted(names):
        if name not in ordered:
            ordered.append(name)
    return ordered


def main():
    default_path = Path(__file__).resolve().parents[1] / "src/main/resources/benchmark-baseline.json"
    baseline_path = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else default_path

    if not baseline_path.exists():
        print(f"Baseline file not found: {baseline_path}", file=sys.stderr)
        return 1

    data = json.loads(baseline_path.read_text(encoding="utf-8"))
    jdk_baselines = data.get("jdkBaselines", {})
    if not isinstance(jdk_baselines, dict) or not jdk_baselines:
        print("No jdkBaselines found in baseline file.", file=sys.stderr)
        return 1

    jdk_keys = ordered_jdk_keys(jdk_baselines)
    bench_names = extract_benchmarks(jdk_baselines, jdk_keys)

    print(f"# Local JDK baseline comparison ({baseline_path})")
    print("")
    print("| JDK | Version | UpdatedAt | OS | CPU |")
    print("| --- | --- | --- | --- | --- |")
    for key in jdk_keys:
        meta = jdk_baselines.get(key, {}).get("_meta", {})
        version = meta.get("jdk", "-")
        updated_at = meta.get("updatedAt", "-")
        os_name = meta.get("os", "-")
        cpu = meta.get("cpu", "-")
        print(f"| {key} | {version} | {updated_at} | {os_name} | {cpu} |")

    print("")
    header = ["Benchmark (ns/op)"] + [f"{key} ({jdk_baselines[key].get('_meta', {}).get('jdk', '-')})" for key in jdk_keys]
    print("| " + " | ".join(header) + " |")
    print("| " + " | ".join(["---"] + ["---:"] * len(jdk_keys)) + " |")

    for name in bench_names:
        row = [name]
        for key in jdk_keys:
            baselines = jdk_baselines.get(key, {}).get("baselines", {})
            metric = baselines.get(name, {}) if isinstance(baselines, dict) else {}
            score = metric.get("scoreNs") if isinstance(metric, dict) else None
            try:
                score = float(score) if score is not None else None
            except (TypeError, ValueError):
                score = None
            row.append(fmt_score(score))
        print("| " + " | ".join(row) + " |")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
