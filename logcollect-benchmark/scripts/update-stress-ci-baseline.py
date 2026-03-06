#!/usr/bin/env python3
"""
Update CI stress-gate baselines from repeated stress smoke samples.

Input file must be JSONL where each line is either:
  1) raw StressTestReport JSON (scenario -> metrics), or
  2) {"results": {...}} where results has the same shape.
"""

import argparse
import json
import os
import platform
from datetime import datetime, timezone


def parse_args():
    parser = argparse.ArgumentParser(
        description="Update ciStressBaselines.<profile>.<jdk>.stressGate from stress sample JSONL."
    )
    parser.add_argument("samples_jsonl")
    parser.add_argument("baseline_json")
    parser.add_argument("--profile", default="github-ubuntu-latest")
    parser.add_argument("--jdk-key", default=None, help="Target key, e.g. jdk17")
    parser.add_argument("--jdk-version", default=None)
    parser.add_argument("--java-home", default=None)
    parser.add_argument("--jvm-opts", default=None)
    parser.add_argument("--spring-profiles", default=None)
    parser.add_argument("--max-thread-cap", default=None)
    parser.add_argument("--task-timeout-seconds", default=None)
    parser.add_argument("--runner-memory-mb", default=None)
    parser.add_argument("--updated-by", default=None)
    parser.add_argument("--note", default="Auto-updated by update-stress-ci-baseline.py")
    parser.add_argument("--github-run-id", default=None)
    parser.add_argument("--github-run-number", default=None)
    parser.add_argument("--github-run-attempt", default=None)
    parser.add_argument("--github-sha", default=None)
    parser.add_argument("--github-ref-name", default=None)
    parser.add_argument("--github-workflow", default=None)
    parser.add_argument("--github-repository", default=None)
    parser.add_argument("--framework-version", default=None)
    parser.add_argument("--commit-hash", default=None)
    parser.add_argument("--build-strategy", default="per-jdk")

    parser.add_argument("--sample-runs", type=int, default=10)
    parser.add_argument("--min-samples-per-metric", type=int, default=5)
    parser.add_argument("--trim-count-each-side", type=int, default=1)

    parser.add_argument("--gate-runs", type=int, default=5)
    parser.add_argument("--gate-min-samples-after-filter", type=int, default=3)
    parser.add_argument("--gate-trim-count-each-side", type=int, default=1)

    parser.add_argument("--throughput-min-multiplier", type=float, default=0.80)
    parser.add_argument("--ratio-min-multiplier", type=float, default=0.75)
    parser.add_argument("--gc-max-multiplier", type=float, default=4.0)
    parser.add_argument("--gc-absolute-min-percent", type=float, default=2.0)
    parser.add_argument("--gc-absolute-max-percent", type=float, default=5.0)

    parser.add_argument(
        "--default-ratio-key",
        action="append",
        default=[],
        help="Ratio baseline key in numerator/denominator format (can repeat).",
    )
    return parser.parse_args()


def iso_now_utc():
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_major(java_ver):
    if not java_ver:
        return None
    raw = java_ver.strip()
    if not raw:
        return None
    if raw.startswith("1."):
        raw = raw[2:]
    part = raw.split(".")[0]
    try:
        return int(part)
    except Exception:
        return None


def infer_jdk_key(java_version):
    major = parse_major(java_version)
    if major is None:
        return "unknown"
    return "jdk{}".format(major)


def read_json_if_exists(path):
    if not os.path.exists(path):
        return {}
    with open(path, "r", encoding="utf-8") as file:
        data = json.load(file)
    return data if isinstance(data, dict) else {}


def load_sample_results(samples_jsonl):
    samples = []
    with open(samples_jsonl, "r", encoding="utf-8") as file:
        for lineno, line in enumerate(file, 1):
            raw = line.strip()
            if not raw:
                continue
            try:
                node = json.loads(raw)
            except Exception as ex:
                raise ValueError("Invalid JSON on line {}: {}".format(lineno, ex))

            if isinstance(node, dict) and isinstance(node.get("results"), dict):
                results = node["results"]
            elif isinstance(node, dict):
                results = node
            else:
                raise ValueError("Invalid sample shape on line {}".format(lineno))

            samples.append(results)
    return samples


def percentile(sorted_values, pct):
    if not sorted_values:
        return 0.0
    if len(sorted_values) == 1:
        return float(sorted_values[0])
    clamped = max(0.0, min(100.0, float(pct)))
    pos = (clamped / 100.0) * (len(sorted_values) - 1)
    lower = int(pos)
    upper = lower if pos == lower else lower + 1
    if lower == upper:
        return float(sorted_values[lower])
    lower_v = float(sorted_values[lower])
    upper_v = float(sorted_values[upper])
    frac = pos - lower
    return lower_v + (upper_v - lower_v) * frac


def aggregate(values, trim_each_side, min_keep):
    if not values:
        raise ValueError("No samples to aggregate.")
    sorted_values = sorted(float(v) for v in values)
    total = len(sorted_values)
    if total < min_keep:
        raise ValueError("Not enough samples: got={}, require={}".format(total, min_keep))

    max_trim_by_count = max(0, (total - min_keep) // 2)
    trim = min(max(0, trim_each_side), max_trim_by_count)
    kept = sorted_values[trim: total - trim]
    if len(kept) < min_keep:
        kept = sorted_values
        trim = 0

    trimmed_mean = sum(kept) / len(kept)
    return {
        "runs": total,
        "kept": len(kept),
        "trimCountEachSide": trim,
        "min": min(sorted_values),
        "max": max(sorted_values),
        "p50": percentile(sorted_values, 50.0),
        "p90": percentile(sorted_values, 90.0),
        "trimmedMean": trimmed_mean,
    }


def collect_series(samples, scenario, field, allow_zero):
    values = []
    for sample in samples:
        scenario_node = sample.get(scenario)
        if not isinstance(scenario_node, dict):
            continue
        value = scenario_node.get(field)
        if not isinstance(value, (int, float)):
            continue
        number = float(value)
        if allow_zero:
            if number < 0.0:
                continue
        else:
            if number <= 0.0:
                continue
        values.append(number)
    return values


def collect_ratio_series(samples, numerator, denominator):
    values = []
    for sample in samples:
        top = sample.get(numerator)
        bot = sample.get(denominator)
        if not isinstance(top, dict) or not isinstance(bot, dict):
            continue
        top_v = top.get("throughput")
        bot_v = bot.get("throughput")
        if not isinstance(top_v, (int, float)) or not isinstance(bot_v, (int, float)):
            continue
        if float(bot_v) <= 0.0:
            continue
        values.append(float(top_v) / float(bot_v))
    return values


def existing_gate_config(existing, profile, jdk_key):
    ci = existing.get("ciStressBaselines", {})
    if isinstance(ci, dict):
        profile_node = ci.get(profile, {})
        if isinstance(profile_node, dict):
            jdk_node = profile_node.get(jdk_key, {})
            if isinstance(jdk_node, dict):
                gate = jdk_node.get("stressGate", {})
                if isinstance(gate, dict):
                    return gate

    jdk_baselines = existing.get("jdkBaselines", {})
    if isinstance(jdk_baselines, dict):
        jdk_node = jdk_baselines.get(jdk_key, {})
        if isinstance(jdk_node, dict):
            ratios = jdk_node.get("ratios", {})
            if isinstance(ratios, dict):
                gate = ratios.get("stressGate", {})
                if isinstance(gate, dict):
                    return gate

    ratios = existing.get("ratios", {})
    if isinstance(ratios, dict):
        gate = ratios.get("stressGate", {})
        if isinstance(gate, dict):
            return gate
    return {}


def keys_from_map(node, field):
    if not isinstance(node, dict):
        return []
    obj = node.get(field, {})
    if not isinstance(obj, dict):
        return []
    return list(obj.keys())


def round_stats(stats, digits):
    rounded = {}
    for key, value in stats.items():
        rounded[key] = {
            "runs": int(value["runs"]),
            "kept": int(value["kept"]),
            "trimCountEachSide": int(value["trimCountEachSide"]),
            "min": round(float(value["min"]), digits),
            "max": round(float(value["max"]), digits),
            "p50": round(float(value["p50"]), digits),
            "p90": round(float(value["p90"]), digits),
            "trimmedMean": round(float(value["trimmedMean"]), digits),
        }
    return rounded


def ensure_sample_count(name, values, min_samples):
    if len(values) < min_samples:
        raise ValueError(
            "Metric '{}' has insufficient samples: got={}, require>={}".format(
                name, len(values), min_samples
            )
        )


def clean_optional(value):
    if value is None:
        return None
    raw = str(value).strip()
    return raw if raw else None


def to_int_if_possible(value):
    cleaned = clean_optional(value)
    if cleaned is None:
        return None
    try:
        return int(cleaned)
    except Exception:
        return cleaned


def main():
    args = parse_args()
    samples = load_sample_results(args.samples_jsonl)
    if not samples:
        raise SystemExit("No samples found in {}".format(args.samples_jsonl))

    if len(samples) != args.sample_runs:
        print(
            "WARN: sample count mismatch, expected {}, got {}.".format(
                args.sample_runs, len(samples)
            )
        )

    java_version = args.jdk_version or platform.java_ver()[0] or "unknown"
    jdk_key = args.jdk_key or infer_jdk_key(java_version)
    existing = read_json_if_exists(args.baseline_json)
    base_gate = existing_gate_config(existing, args.profile, jdk_key)

    throughput_keys = keys_from_map(base_gate, "throughputBaselines")
    if not throughput_keys:
        discovered = set()
        for sample in samples:
            discovered.update(sample.keys())
        throughput_keys = sorted(discovered)

    gc_keys = keys_from_map(base_gate, "gcBaselines")
    if not gc_keys:
        gc_keys = list(throughput_keys)

    ratio_keys = keys_from_map(base_gate, "ratioBaselines")
    if not ratio_keys:
        ratio_keys = args.default_ratio_key or [
            "isolated-8t-clean/isolated-1t-clean",
            "e2e-8t-clean/e2e-1t-clean",
        ]

    throughput_stats = {}
    for key in throughput_keys:
        series = collect_series(samples, key, "throughput", allow_zero=False)
        ensure_sample_count("throughput/{}".format(key), series, args.min_samples_per_metric)
        throughput_stats[key] = aggregate(series, args.trim_count_each_side, args.min_samples_per_metric)

    gc_stats = {}
    for key in gc_keys:
        series = collect_series(samples, key, "gcOverheadPercent", allow_zero=True)
        ensure_sample_count("gc/{}".format(key), series, args.min_samples_per_metric)
        gc_stats[key] = aggregate(series, args.trim_count_each_side, args.min_samples_per_metric)

    ratio_stats = {}
    for key in ratio_keys:
        if "/" not in key:
            raise ValueError("Invalid ratio key '{}', expected numerator/denominator".format(key))
        numerator, denominator = key.split("/", 1)
        series = collect_ratio_series(samples, numerator, denominator)
        ensure_sample_count("ratio/{}".format(key), series, args.min_samples_per_metric)
        ratio_stats[key] = aggregate(series, args.trim_count_each_side, args.min_samples_per_metric)

    throughput_baselines = {
        key: round(value["trimmedMean"], 1) for key, value in throughput_stats.items()
    }
    ratio_baselines = {
        key: round(value["trimmedMean"], 3) for key, value in ratio_stats.items()
    }
    gc_baselines = {
        key: round(value["trimmedMean"], 2) for key, value in gc_stats.items()
    }

    now = iso_now_utc()
    refresh_date = datetime.now(timezone.utc).date().isoformat()
    updated_by = args.updated_by or os.environ.get("GITHUB_ACTOR") or os.environ.get("USER") or "unknown"
    framework_version = clean_optional(args.framework_version) or "unknown"
    commit_hash = clean_optional(args.commit_hash) or clean_optional(args.github_sha) or "unknown"
    build_strategy = clean_optional(args.build_strategy) or "per-jdk"
    meta = {
        "updatedAt": now,
        "updatedBy": updated_by,
        "profile": args.profile,
        "jdk": java_version,
        "jdkKey": jdk_key,
        "javaHome": args.java_home or os.environ.get("JAVA_HOME", "unknown"),
        "os": "{} {}".format(platform.system(), platform.release()),
        "cpu": platform.machine() or platform.processor() or "unknown",
        "sampleRuns": len(samples),
        "sampleMinPerMetric": args.min_samples_per_metric,
        "sampleTrimCountEachSide": args.trim_count_each_side,
        "note": args.note,
        "frameworkVersion": framework_version,
        "commitHash": commit_hash,
        "refreshDate": refresh_date,
        "runs": len(samples),
        "buildStrategy": build_strategy,
    }

    optional_meta = {
        "jvmOpts": clean_optional(args.jvm_opts),
        "springProfiles": clean_optional(args.spring_profiles),
        "maxThreadCap": to_int_if_possible(args.max_thread_cap),
        "taskTimeoutSeconds": to_int_if_possible(args.task_timeout_seconds),
        "runnerMemoryMb": to_int_if_possible(args.runner_memory_mb),
        "githubRunId": to_int_if_possible(args.github_run_id),
        "githubRunNumber": to_int_if_possible(args.github_run_number),
        "githubRunAttempt": to_int_if_possible(args.github_run_attempt),
        "githubSha": clean_optional(args.github_sha),
        "githubRefName": clean_optional(args.github_ref_name),
        "githubWorkflow": clean_optional(args.github_workflow),
        "githubRepository": clean_optional(args.github_repository),
    }
    for key, value in optional_meta.items():
        if value is not None:
            meta[key] = value

    stress_gate = {
        "aggregation": {
            "runs": max(1, args.gate_runs),
            "trimCountEachSide": max(0, args.gate_trim_count_each_side),
            "minSamplesAfterFilter": max(1, args.gate_min_samples_after_filter),
        },
        "throughputMinMultiplier": args.throughput_min_multiplier,
        "ratioMinMultiplier": args.ratio_min_multiplier,
        "gcMaxMultiplier": args.gc_max_multiplier,
        "gcAbsoluteMinPercent": args.gc_absolute_min_percent,
        "gcAbsoluteMaxPercent": args.gc_absolute_max_percent,
        "throughputBaselines": throughput_baselines,
        "ratioBaselines": ratio_baselines,
        "gcBaselines": gc_baselines,
        "stats": {
            "throughput": round_stats(throughput_stats, 2),
            "ratio": round_stats(ratio_stats, 4),
            "gc": round_stats(gc_stats, 4),
        },
    }

    ci_root = existing.get("ciStressBaselines", {})
    if not isinstance(ci_root, dict):
        ci_root = {}
    profile_root = ci_root.get(args.profile, {})
    if not isinstance(profile_root, dict):
        profile_root = {}
    profile_meta = profile_root.get("_meta", {})
    if not isinstance(profile_meta, dict):
        profile_meta = {}
    profile_meta.update({
        "frameworkVersion": framework_version,
        "commitHash": commit_hash,
        "refreshDate": refresh_date,
        "runs": len(samples),
        "buildStrategy": build_strategy,
        "updatedAt": now,
        "updatedBy": updated_by,
    })
    profile_root["_meta"] = profile_meta
    profile_root[jdk_key] = {
        "_meta": meta,
        "stressGate": stress_gate,
    }
    ci_root[args.profile] = profile_root
    existing["ciStressBaselines"] = ci_root

    with open(args.baseline_json, "w", encoding="utf-8") as file:
        json.dump(existing, file, indent=2, ensure_ascii=False)
        file.write("\n")

    print(
        "Updated ciStressBaselines.{}.{} in {}".format(
            args.profile, jdk_key, args.baseline_json
        )
    )
    print("  sampleRuns={}".format(len(samples)))
    for key in sorted(throughput_baselines):
        baseline = throughput_baselines[key]
        p50 = stress_gate["stats"]["throughput"][key]["p50"]
        p90 = stress_gate["stats"]["throughput"][key]["p90"]
        print("  throughput {:<35} baseline={:>10.1f} p50={:>10.1f} p90={:>10.1f}".format(
            key, baseline, p50, p90
        ))


if __name__ == "__main__":
    main()
