#!/bin/bash
# Build once and refresh benchmark-baseline.json for JDK 8/11/17/21 sequentially.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
BENCH_DIR="$ROOT_DIR/logcollect-benchmark"
RESULT_DIR="$BENCH_DIR/target/jmh-results"
BASELINE_JSON="$BENCH_DIR/src/main/resources/benchmark-baseline.json"
JAR_FILE="$BENCH_DIR/target/logcollect-benchmark.jar"
PY_UPDATE="$BENCH_DIR/scripts/update-baseline-json.py"

JMH_RUN_ARGS=(-f 2 -wi 3 -i 5 -w 1s -r 1s -t 1 -tu ns -bm avgt)
JMH_ARGS_SUMMARY="-f 2 -wi 3 -i 5 -w 1s -r 1s -t 1 -tu ns -bm avgt"
JMH_JVM_ARGS="${JMH_JVM_ARGS:--Xms512m -Xmx512m -XX:+UseG1GC}"
DEFAULT_JDK_KEY="${DEFAULT_JDK_KEY:-jdk17}"
BENCH_FILTER="SecurityPipelineBenchmark\\.pipeline_cleanMessage_emptyMdc|SecurityPipelineBenchmark\\.pipeline_perCall_newInstance|SecurityPipelineBenchmark\\.pipeline_sensitiveMessage_typicalMdc|SecurityPipelineBenchmark\\.pipeline_withThrowable|SanitizeBenchmark\\.sanitize_cleanMessage|SanitizeBenchmark\\.sanitize_withInjection|SanitizeBenchmark\\.sanitize_longMessage|ReflectionVsInterfaceBenchmark\\.reflection_getMethods_everyTime|ReflectionVsInterfaceBenchmark\\.interface_virtualDispatch|ReflectionVsInterfaceBenchmark\\.interface_noop|MdcCopyBenchmark\\.fullCopy_typical|MdcCopyBenchmark\\.lazyCopy_typical_clean|MdcCopyBenchmark\\.fullCopy_large|MdcCopyBenchmark\\.lazyCopy_large_clean"

mkdir -p "$RESULT_DIR"
cd "$ROOT_DIR"

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

JDK8_HOME="$(require_java_home "1.8")"
JDK11_HOME="$(require_java_home "11")"
JDK17_HOME="$(require_java_home "17")"
JDK21_HOME="$(require_java_home "21")"

echo "=== Build benchmark module with JDK8-compatible toolchain ==="
JAVA_HOME="$JDK8_HOME" PATH="$JDK8_HOME/bin:$PATH" mvn -pl logcollect-benchmark -am package -DskipTests -q

run_for_jdk() {
  local major="$1"
  local java_home="$2"
  local java_bin="$java_home/bin/java"
  local jdk_key="jdk${major}"
  local result_json="$RESULT_DIR/full-baseline-${jdk_key}.json"
  local jdk_version
  jdk_version="$("$java_bin" -version 2>&1 | awk -F\" '/version/ {print $2; exit}')"

  echo "=== Running JMH for ${jdk_key} (${jdk_version}) ==="
  "$java_bin" -jar "$JAR_FILE" \
    "${JMH_RUN_ARGS[@]}" \
    -jvmArgsAppend "$JMH_JVM_ARGS" \
    -rf json -rff "$result_json" \
    "$BENCH_FILTER"

  echo "=== Updating baseline JSON for ${jdk_key} ==="
  python3 "$PY_UPDATE" \
    "$result_json" \
    "$BASELINE_JSON" \
    --jdk-key "$jdk_key" \
    --default-jdk-key "$DEFAULT_JDK_KEY" \
    --jdk-version "$jdk_version" \
    --java-home "$java_home" \
    --jvm-args "$JMH_JVM_ARGS" \
    --jmh-args "$JMH_ARGS_SUMMARY"
}

run_for_jdk 8 "$JDK8_HOME"
run_for_jdk 11 "$JDK11_HOME"
run_for_jdk 17 "$JDK17_HOME"
run_for_jdk 21 "$JDK21_HOME"

echo "=== Done ==="
echo "Updated: $BASELINE_JSON"
echo "Raw result files:"
echo "  $RESULT_DIR/full-baseline-jdk8.json"
echo "  $RESULT_DIR/full-baseline-jdk11.json"
echo "  $RESULT_DIR/full-baseline-jdk17.json"
echo "  $RESULT_DIR/full-baseline-jdk21.json"
