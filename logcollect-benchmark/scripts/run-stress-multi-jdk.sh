#!/bin/bash
# Multi-JDK practical throughput benchmark runner.
# Goal: execute the stress benchmark on JDK 8/11/17/21 with production-like settings,
# then emit machine-readable and markdown summaries for README.
#
# Scenario mapping (from StressTestRunner):
# - baseline-1t-nop: Logger -> NOP only, baseline upper bound.
# - isolated-*     : bypass Logger.callAppenders(), measure framework-only pipeline overhead.
# - e2e-*          : full SLF4J/Logback path, with LogCollect appender attached on ROOT.
# - degrade        : reliability/degradation path (only in --full mode).
#
# Production-like approximations:
# - spring profile: stress,with-file-output
#   * keeps Logback async file appender enabled (neverBlock=true) to resemble production async output.
# - GC & heap: G1, fixed heap, AlwaysPreTouch.
# - same load profile across JDKs, same machine, same execution order.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
BENCH_DIR="$ROOT_DIR/logcollect-benchmark"
JAR_FILE="$BENCH_DIR/target/logcollect-benchmark.jar"
RESULT_DIR="$BENCH_DIR/target/stress-results/multi-jdk"

# Defaults can be overridden by env vars.
STRESS_MODE="${STRESS_MODE:-full}"                  # full | smoke
RUNS_PER_JDK="${RUNS_PER_JDK:-1}"                   # repeat count per JDK
JDK_SPECS="${JDK_SPECS:-1.8 11 17 21}"              # execution order
BUILD_STRATEGY="${BUILD_STRATEGY:-once}"            # once | per-jdk
SPRING_PROFILES="${SPRING_PROFILES:-stress,with-file-output}"
MAX_THREAD_CAP="${MAX_THREAD_CAP:-128}"
TASK_TIMEOUT_SECONDS="${TASK_TIMEOUT_SECONDS:-120}"
JVM_HEAP_OPTS="${JVM_HEAP_OPTS:--Xms2g -Xmx2g}"
JVM_COMMON_OPTS="${JVM_COMMON_OPTS:--XX:+UseG1GC -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints}"

MODE_ARG=()
if [[ "$STRESS_MODE" == "full" ]]; then
  MODE_ARG=(--full)
elif [[ "$STRESS_MODE" == "smoke" ]]; then
  MODE_ARG=()
else
  echo "Unsupported STRESS_MODE: $STRESS_MODE (allowed: full|smoke)" >&2
  exit 1
fi

if [[ "$BUILD_STRATEGY" != "once" && "$BUILD_STRATEGY" != "per-jdk" ]]; then
  echo "Unsupported BUILD_STRATEGY: $BUILD_STRATEGY (allowed: once|per-jdk)" >&2
  exit 1
fi

mkdir -p "$RESULT_DIR"

RESULT_JSONL="$RESULT_DIR/results.jsonl"
SUMMARY_JSON="$RESULT_DIR/throughput-summary.json"
SUMMARY_MD="$RESULT_DIR/throughput-summary.md"
META_TXT="$RESULT_DIR/run-meta.txt"

write_meta() {
  cat > "$META_TXT" <<EOF
date=$(date '+%Y-%m-%d %H:%M:%S %z')
machine=$(uname -a)
stress_mode=$STRESS_MODE
runs_per_jdk=$RUNS_PER_JDK
jdk_specs=$JDK_SPECS
build_strategy=$BUILD_STRATEGY
spring_profiles=$SPRING_PROFILES
max_thread_cap=$MAX_THREAD_CAP
task_timeout_seconds=$TASK_TIMEOUT_SECONDS
jvm_heap_opts=$JVM_HEAP_OPTS
jvm_common_opts=$JVM_COMMON_OPTS
EOF
}

require_java_home() {
  local spec="$1"
  local home
  home="$(/usr/libexec/java_home -v "$spec" 2>/dev/null || true)"
  if [[ -z "$home" ]]; then
    echo "Missing JDK for version spec: $spec" >&2
    exit 1
  fi
  echo "$home"
}

jdk_key_from_java() {
  local java_bin="$1"
  local spec
  spec="$("$java_bin" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version =/ {print $2; exit}')"
  if [[ "$spec" == 1.* ]]; then
    spec="${spec#1.}"
  fi
  spec="${spec%%.*}"
  echo "jdk${spec}"
}

build_with_java_home() {
  local java_home="$1"
  JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" mvn -pl logcollect-benchmark -am package -DskipTests -Dmaven.javadoc.skip=true -q
}

build_once() {
  if [[ "$BUILD_STRATEGY" != "once" ]]; then
    return
  fi
  local first_spec first_home
  first_spec="$(echo "$JDK_SPECS" | awk '{print $1}')"
  first_home="$(require_java_home "$first_spec")"
  echo "=== Building benchmark module once (JAVA_HOME=$first_home) ==="
  build_with_java_home "$first_home"
}

run_benchmark_for_jdk() {
  local spec="$1"
  local java_home java_bin jdk_key jdk_version
  java_home="$(require_java_home "$spec")"
  java_bin="$java_home/bin/java"
  jdk_key="$(jdk_key_from_java "$java_bin")"
  jdk_version="$("$java_bin" -version 2>&1 | awk -F\" '/version/ {print $2; exit}')"

  if [[ "$BUILD_STRATEGY" == "per-jdk" ]]; then
    echo "=== Building benchmark module for ${jdk_key} (${jdk_version}) ==="
    build_with_java_home "$java_home"
  fi

  echo "=== Running throughput benchmark for ${jdk_key} (${jdk_version}) ==="
  for run in $(seq 1 "$RUNS_PER_JDK"); do
    local log_file json_line result_json
    log_file="$RESULT_DIR/${jdk_key}-run${run}.log"
    echo "=== ${jdk_key} run ${run}/${RUNS_PER_JDK} -> $log_file ==="

    set +e
    "$java_bin" \
      $JVM_HEAP_OPTS \
      $JVM_COMMON_OPTS \
      -Dspring.profiles.active="$SPRING_PROFILES" \
      -Dbenchmark.stress.max-thread-cap="$MAX_THREAD_CAP" \
      -Dbenchmark.stress.task-timeout-seconds="$TASK_TIMEOUT_SECONDS" \
      -cp "$JAR_FILE" \
      com.logcollect.benchmark.stress.StressTestApp \
      ${MODE_ARG[@]+"${MODE_ARG[@]}"} \
      2>&1 | tee "$log_file"
    local status=${PIPESTATUS[0]}
    set -e
    if [[ $status -ne 0 ]]; then
      echo "Benchmark failed for ${jdk_key} run ${run}" >&2
      exit $status
    fi

    json_line="$(grep 'BENCHMARK_RESULTS_JSON=' "$log_file" | tail -n 1 || true)"
    if [[ -z "$json_line" ]]; then
      echo "BENCHMARK_RESULTS_JSON not found in: $log_file" >&2
      exit 1
    fi
    result_json="${json_line#BENCHMARK_RESULTS_JSON=}"

    printf '{"jdkKey":"%s","jdkSpec":"%s","jdkVersion":"%s","javaHome":"%s","run":%d,"mode":"%s","profiles":"%s","maxThreadCap":%d,"taskTimeoutSeconds":%d,"results":%s}\n' \
      "$jdk_key" "$spec" "$jdk_version" "$java_home" "$run" "$STRESS_MODE" "$SPRING_PROFILES" "$MAX_THREAD_CAP" "$TASK_TIMEOUT_SECONDS" "$result_json" \
      >> "$RESULT_JSONL"
  done
}

render_summary() {
  python3 - "$RESULT_JSONL" "$SUMMARY_JSON" "$SUMMARY_MD" "$META_TXT" <<'PY'
import datetime
import json
import statistics
import sys
from pathlib import Path

jsonl_path = Path(sys.argv[1])
summary_json_path = Path(sys.argv[2])
summary_md_path = Path(sys.argv[3])
meta_txt_path = Path(sys.argv[4])

lines = [line.strip() for line in jsonl_path.read_text(encoding="utf-8").splitlines() if line.strip()]
if not lines:
    raise SystemExit("No benchmark records found.")

records = [json.loads(line) for line in lines]

jdk_order = ["jdk8", "jdk11", "jdk17", "jdk21"]
seen_jdks = []
for k in jdk_order:
    if any(r.get("jdkKey") == k for r in records):
        seen_jdks.append(k)
for r in records:
    k = r.get("jdkKey")
    if k not in seen_jdks:
        seen_jdks.append(k)

scenario_order = [
    "baseline-1t-nop",
    "isolated-1t-clean",
    "isolated-8t-clean",
    "isolated-8t-sensitive",
    "isolated-32t-clean",
    "isolated-8t-with-throwable",
    "e2e-1t-clean",
    "e2e-8t-clean",
    "e2e-8t-sensitive",
    "degrade",
]

def aggregate_metric(values):
    if not values:
        return None
    if len(values) == 1:
        return {"avg": values[0], "min": values[0], "max": values[0], "runs": 1}
    return {
        "avg": statistics.mean(values),
        "min": min(values),
        "max": max(values),
        "runs": len(values),
    }

# aggregates[jdk][scenario][metric] -> stats
aggregates = {}
jdk_meta = {}
for jdk in seen_jdks:
    jdk_records = [r for r in records if r.get("jdkKey") == jdk]
    if not jdk_records:
        continue
    jdk_meta[jdk] = {
        "jdkVersion": jdk_records[0].get("jdkVersion"),
        "javaHome": jdk_records[0].get("javaHome"),
        "mode": jdk_records[0].get("mode"),
        "profiles": jdk_records[0].get("profiles"),
        "runs": len(jdk_records),
    }
    scenario_names = set()
    for rec in jdk_records:
        scenario_names.update((rec.get("results") or {}).keys())

    per_scenario = {}
    for s in scenario_order:
        if s not in scenario_names:
            continue
        thr = []
        lat = []
        gc = []
        for rec in jdk_records:
            scenario = (rec.get("results") or {}).get(s)
            if not scenario:
                continue
            if "throughput" in scenario:
                thr.append(float(scenario["throughput"]))
            if "avgLatencyNanos" in scenario:
                lat.append(float(scenario["avgLatencyNanos"]))
            if "gcOverheadPercent" in scenario:
                gc.append(float(scenario["gcOverheadPercent"]))
        per_scenario[s] = {
            "throughput": aggregate_metric(thr),
            "avgLatencyNanos": aggregate_metric(lat),
            "gcOverheadPercent": aggregate_metric(gc),
        }
    aggregates[jdk] = per_scenario

summary = {
    "generatedAt": datetime.datetime.now().isoformat(timespec="seconds"),
    "records": records,
    "jdkMeta": jdk_meta,
    "aggregates": aggregates,
}
summary_json_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

def fmt_int(v):
    if v is None:
        return "-"
    return f"{int(round(v)):,}"

def fmt_float(v, digits=2):
    if v is None:
        return "-"
    return f"{v:.{digits}f}"

def metric(jdk, scenario, metric_name):
    return (
        aggregates.get(jdk, {})
        .get(scenario, {})
        .get(metric_name, {})
        .get("avg")
    )

def row_for_metric(scenario, metric_name, suffix):
    row = [f"`{scenario}` {suffix}"]
    for jdk in seen_jdks:
        value = metric(jdk, scenario, metric_name)
        if metric_name == "throughput":
            row.append(fmt_int(value))
        elif metric_name == "avgLatencyNanos":
            row.append(fmt_int(value))
        else:
            row.append(fmt_float(value, 2))
    return row

throughput_scenarios = [
    "baseline-1t-nop",
    "isolated-1t-clean",
    "isolated-8t-clean",
    "isolated-8t-sensitive",
    "e2e-1t-clean",
    "e2e-8t-clean",
    "e2e-8t-sensitive",
]
if any("isolated-32t-clean" in aggregates.get(j, {}) for j in seen_jdks):
    throughput_scenarios.insert(4, "isolated-32t-clean")
if any("isolated-8t-with-throwable" in aggregates.get(j, {}) for j in seen_jdks):
    throughput_scenarios.append("isolated-8t-with-throwable")
if any("degrade" in aggregates.get(j, {}) for j in seen_jdks):
    throughput_scenarios.append("degrade")

header = ["场景"] + [f"{jdk} 吞吐(logs/s)" for jdk in seen_jdks]
lines_out = []
lines_out.append("# 多 JDK 吞吐量实测结果")
lines_out.append("")
if meta_txt_path.exists():
    lines_out.append("## 本次执行参数")
    lines_out.append("")
    lines_out.append("```text")
    lines_out.append(meta_txt_path.read_text(encoding="utf-8").rstrip())
    lines_out.append("```")
    lines_out.append("")

lines_out.append("## 吞吐量（平均值）")
lines_out.append("")
lines_out.append("| " + " | ".join(header) + " |")
lines_out.append("|" + "|".join(["---"] * len(header)) + "|")
for s in throughput_scenarios:
    row = row_for_metric(s, "throughput", "吞吐")
    lines_out.append("| " + " | ".join(row) + " |")
lines_out.append("")

lat_header = ["场景"] + [f"{jdk} 延迟(ns/log)" for jdk in seen_jdks]
lines_out.append("## 平均延迟（平均值）")
lines_out.append("")
lines_out.append("| " + " | ".join(lat_header) + " |")
lines_out.append("|" + "|".join(["---"] * len(lat_header)) + "|")
for s in throughput_scenarios:
    row = row_for_metric(s, "avgLatencyNanos", "延迟")
    lines_out.append("| " + " | ".join(row) + " |")
lines_out.append("")

# Relative ratios for e2e-8t-clean, baseline JDK17 if available.
base_jdk = "jdk17" if "jdk17" in seen_jdks else seen_jdks[0]
base_value = metric(base_jdk, "e2e-8t-clean", "throughput")
lines_out.append("## 关键比例（`e2e-8t-clean` 相对基准）")
lines_out.append("")
lines_out.append(f"- 基准 JDK: `{base_jdk}`")
if base_value and base_value > 0:
    for jdk in seen_jdks:
        val = metric(jdk, "e2e-8t-clean", "throughput")
        if val is None:
            continue
        ratio = val / base_value
        lines_out.append(f"- `{jdk}`: {val:,.0f} logs/s（{ratio:.2%} of {base_jdk}）")
else:
    lines_out.append("- `e2e-8t-clean` 数据不足，未计算比例。")
lines_out.append("")

summary_md_path.write_text("\n".join(lines_out), encoding="utf-8")
print(f"Summary JSON: {summary_json_path}")
print(f"Summary MD  : {summary_md_path}")
PY
}

echo "=== Multi-JDK throughput benchmark ==="
write_meta
: > "$RESULT_JSONL"
build_once

for spec in $JDK_SPECS; do
  run_benchmark_for_jdk "$spec"
done

render_summary

echo "=== Done ==="
echo "Raw logs and records:"
echo "  $RESULT_DIR"
echo "Markdown summary:"
echo "  $SUMMARY_MD"
