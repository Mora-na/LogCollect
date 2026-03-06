#!/bin/bash
# Collect stress smoke samples on current machine and update CI stress baseline.
#
# Recommended usage:
#   BASELINE_PROFILE=github-ubuntu-latest RUNS=10 ./logcollect-benchmark/scripts/update-stress-ci-baseline.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
BENCH_DIR="$ROOT_DIR/logcollect-benchmark"
RESULT_DIR="$BENCH_DIR/target/stress-results/ci-baseline"
BASELINE_JSON="$BENCH_DIR/src/main/resources/benchmark-baseline.json"
JAR_FILE="$BENCH_DIR/target/logcollect-benchmark.jar"
PY_UPDATE="$BENCH_DIR/scripts/update-stress-ci-baseline.py"

RUNS="${RUNS:-10}"
BASELINE_PROFILE="${BASELINE_PROFILE:-github-ubuntu-latest}"
SAMPLE_MIN_PER_METRIC="${SAMPLE_MIN_PER_METRIC:-5}"
SAMPLE_TRIM_EACH_SIDE="${SAMPLE_TRIM_EACH_SIDE:-1}"

GATE_RUNS="${GATE_RUNS:-5}"
GATE_MIN_SAMPLES_AFTER_FILTER="${GATE_MIN_SAMPLES_AFTER_FILTER:-3}"
GATE_TRIM_EACH_SIDE="${GATE_TRIM_EACH_SIDE:-1}"

THROUGHPUT_MIN_MULTIPLIER="${THROUGHPUT_MIN_MULTIPLIER:-0.8}"
RATIO_MIN_MULTIPLIER="${RATIO_MIN_MULTIPLIER:-0.75}"
GC_MAX_MULTIPLIER="${GC_MAX_MULTIPLIER:-4.0}"
GC_ABSOLUTE_MIN_PERCENT="${GC_ABSOLUTE_MIN_PERCENT:-2.0}"
GC_ABSOLUTE_MAX_PERCENT="${GC_ABSOLUTE_MAX_PERCENT:-5.0}"

JVM_OPTS="${JVM_OPTS:--Xms512m -Xmx512m -XX:+UseG1GC}"
SPRING_PROFILES="${SPRING_PROFILES:-stress}"
MAX_THREAD_CAP="${MAX_THREAD_CAP:-128}"
TASK_TIMEOUT_SECONDS="${TASK_TIMEOUT_SECONDS:-60}"
SKIP_BUILD="${SKIP_BUILD:-0}"
GITHUB_RUN_ID_VAL="${GITHUB_RUN_ID:-}"
GITHUB_RUN_NUMBER_VAL="${GITHUB_RUN_NUMBER:-}"
GITHUB_RUN_ATTEMPT_VAL="${GITHUB_RUN_ATTEMPT:-}"
GITHUB_SHA_VAL="${GITHUB_SHA:-}"
GITHUB_REF_NAME_VAL="${GITHUB_REF_NAME:-}"
GITHUB_WORKFLOW_VAL="${GITHUB_WORKFLOW:-}"
GITHUB_REPOSITORY_VAL="${GITHUB_REPOSITORY:-}"
COMMIT_HASH_VAL="${COMMIT_HASH:-$(git rev-parse --short=12 HEAD 2>/dev/null || echo unknown)}"
FRAMEWORK_VERSION_VAL="${FRAMEWORK_VERSION:-unknown}"
BUILD_STRATEGY_VAL="${BUILD_STRATEGY:-per-jdk}"
if [[ "$FRAMEWORK_VERSION_VAL" == "unknown" ]]; then
  FRAMEWORK_VERSION_VAL="$(sed -n 's|.*<version>\(.*\)</version>.*|\1|p' "$ROOT_DIR/pom.xml" | head -n 1)"
  FRAMEWORK_VERSION_VAL="${FRAMEWORK_VERSION_VAL:-unknown}"
fi

detect_runner_memory_mb() {
  if [[ -r /proc/meminfo ]]; then
    awk '/MemTotal:/ {printf "%.0f", $2/1024; exit}' /proc/meminfo
    return 0
  fi
  if command -v sysctl >/dev/null 2>&1; then
    local mem_bytes
    mem_bytes="$(sysctl -n hw.memsize 2>/dev/null || true)"
    if [[ -n "$mem_bytes" ]]; then
      echo $((mem_bytes/1024/1024))
      return 0
    fi
  fi
  echo ""
}

RUNNER_MEMORY_MB="${RUNNER_MEMORY_MB:-$(detect_runner_memory_mb)}"

mkdir -p "$RESULT_DIR"

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
if [[ -z "${JAVA_BIN}" || ! -x "${JAVA_BIN}" ]]; then
  JAVA_BIN="$(command -v java)"
fi

JDK_VERSION="$("$JAVA_BIN" -version 2>&1 | awk -F\" '/version/ {print $2; exit}')"
JAVA_SPEC="$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version =/ {print $2; exit}')"
if [[ "$JAVA_SPEC" == 1.* ]]; then
  JAVA_SPEC="${JAVA_SPEC#1.}"
fi
JAVA_SPEC="${JAVA_SPEC%%.*}"
JDK_KEY="jdk${JAVA_SPEC}"

SAMPLES_JSONL="$RESULT_DIR/stress-samples-${BASELINE_PROFILE}-${JDK_KEY}.jsonl"
META_TXT="$RESULT_DIR/stress-samples-${BASELINE_PROFILE}-${JDK_KEY}.meta.txt"
: > "$SAMPLES_JSONL"

cat > "$META_TXT" <<EOF
date=$(date '+%Y-%m-%d %H:%M:%S %z')
profile=$BASELINE_PROFILE
runs=$RUNS
java=$JAVA_BIN
jdk_version=$JDK_VERSION
jdk_key=$JDK_KEY
spring_profiles=$SPRING_PROFILES
jvm_opts=$JVM_OPTS
max_thread_cap=$MAX_THREAD_CAP
task_timeout_seconds=$TASK_TIMEOUT_SECONDS
runner_memory_mb=$RUNNER_MEMORY_MB
gate_runs=$GATE_RUNS
gate_min_samples_after_filter=$GATE_MIN_SAMPLES_AFTER_FILTER
gate_trim_each_side=$GATE_TRIM_EACH_SIDE
throughput_min_multiplier=$THROUGHPUT_MIN_MULTIPLIER
ratio_min_multiplier=$RATIO_MIN_MULTIPLIER
gc_max_multiplier=$GC_MAX_MULTIPLIER
gc_absolute_min_percent=$GC_ABSOLUTE_MIN_PERCENT
gc_absolute_max_percent=$GC_ABSOLUTE_MAX_PERCENT
github_run_id=$GITHUB_RUN_ID_VAL
github_run_number=$GITHUB_RUN_NUMBER_VAL
github_run_attempt=$GITHUB_RUN_ATTEMPT_VAL
github_sha=$GITHUB_SHA_VAL
github_ref_name=$GITHUB_REF_NAME_VAL
github_workflow=$GITHUB_WORKFLOW_VAL
github_repository=$GITHUB_REPOSITORY_VAL
framework_version=$FRAMEWORK_VERSION_VAL
commit_hash=$COMMIT_HASH_VAL
build_strategy=$BUILD_STRATEGY_VAL
EOF

cd "$ROOT_DIR"

if [[ "$SKIP_BUILD" != "1" ]]; then
  echo "=== Building benchmark module ==="
  mvn -pl logcollect-benchmark -am package -DskipTests -Dmaven.javadoc.skip=true -q
else
  echo "=== Skip build (SKIP_BUILD=1) ==="
fi

echo "=== Collecting stress smoke samples (${RUNS} runs, profile=${BASELINE_PROFILE}, jdk=${JDK_KEY}) ==="
for run in $(seq 1 "$RUNS"); do
  log_file="$RESULT_DIR/${JDK_KEY}-run${run}.log"
  echo "=== Run ${run}/${RUNS} -> ${log_file} ==="
  set +e
  "$JAVA_BIN" \
    $JVM_OPTS \
    -Dspring.profiles.active="$SPRING_PROFILES" \
    -Dbenchmark.stress.max-thread-cap="$MAX_THREAD_CAP" \
    -Dbenchmark.stress.task-timeout-seconds="$TASK_TIMEOUT_SECONDS" \
    -cp "$JAR_FILE" \
    com.logcollect.benchmark.stress.StressTestApp \
    2>&1 | tee "$log_file"
  status=${PIPESTATUS[0]}
  set -e
  if [[ $status -ne 0 ]]; then
    echo "Stress sample run failed at ${run}/${RUNS}" >&2
    exit $status
  fi

  json_line="$(grep 'BENCHMARK_RESULTS_JSON=' "$log_file" | tail -n 1 || true)"
  if [[ -z "$json_line" ]]; then
    echo "BENCHMARK_RESULTS_JSON not found in ${log_file}" >&2
    exit 1
  fi
  result_json="${json_line#BENCHMARK_RESULTS_JSON=}"
  printf '%s\n' "$result_json" >> "$SAMPLES_JSONL"
done

echo "=== Updating benchmark-baseline.json (ciStressBaselines.${BASELINE_PROFILE}.${JDK_KEY}) ==="
python3 "$PY_UPDATE" \
  "$SAMPLES_JSONL" \
  "$BASELINE_JSON" \
  --profile "$BASELINE_PROFILE" \
  --jdk-key "$JDK_KEY" \
  --jdk-version "$JDK_VERSION" \
  --java-home "${JAVA_HOME:-unknown}" \
  --jvm-opts "$JVM_OPTS" \
  --spring-profiles "$SPRING_PROFILES" \
  --max-thread-cap "$MAX_THREAD_CAP" \
  --task-timeout-seconds "$TASK_TIMEOUT_SECONDS" \
  --runner-memory-mb "$RUNNER_MEMORY_MB" \
  --sample-runs "$RUNS" \
  --min-samples-per-metric "$SAMPLE_MIN_PER_METRIC" \
  --trim-count-each-side "$SAMPLE_TRIM_EACH_SIDE" \
  --gate-runs "$GATE_RUNS" \
  --gate-min-samples-after-filter "$GATE_MIN_SAMPLES_AFTER_FILTER" \
  --gate-trim-count-each-side "$GATE_TRIM_EACH_SIDE" \
  --throughput-min-multiplier "$THROUGHPUT_MIN_MULTIPLIER" \
  --ratio-min-multiplier "$RATIO_MIN_MULTIPLIER" \
  --gc-max-multiplier "$GC_MAX_MULTIPLIER" \
  --gc-absolute-min-percent "$GC_ABSOLUTE_MIN_PERCENT" \
  --gc-absolute-max-percent "$GC_ABSOLUTE_MAX_PERCENT" \
  --github-run-id "$GITHUB_RUN_ID_VAL" \
  --github-run-number "$GITHUB_RUN_NUMBER_VAL" \
  --github-run-attempt "$GITHUB_RUN_ATTEMPT_VAL" \
  --github-sha "$GITHUB_SHA_VAL" \
  --github-ref-name "$GITHUB_REF_NAME_VAL" \
  --github-workflow "$GITHUB_WORKFLOW_VAL" \
  --github-repository "$GITHUB_REPOSITORY_VAL" \
  --framework-version "$FRAMEWORK_VERSION_VAL" \
  --commit-hash "$COMMIT_HASH_VAL" \
  --build-strategy "$BUILD_STRATEGY_VAL" \
  --note "CI stress baseline from ${RUNS} smoke runs (${BASELINE_PROFILE})"

echo "=== Done. Review and commit ==="
echo "  Sample file : $SAMPLES_JSONL"
echo "  Sample meta : $META_TXT"
echo "  Baseline    : $BASELINE_JSON"
echo "  git diff logcollect-benchmark/src/main/resources/benchmark-baseline.json"
