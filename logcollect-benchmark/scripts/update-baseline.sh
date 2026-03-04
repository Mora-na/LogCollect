#!/bin/bash
# Run selected JMH benchmarks and refresh benchmark-baseline.json for current JDK.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
BENCH_DIR="$ROOT_DIR/logcollect-benchmark"
RESULT_DIR="$BENCH_DIR/target/jmh-results"
BASELINE_JSON="$BENCH_DIR/src/main/resources/benchmark-baseline.json"
JAR_FILE="$BENCH_DIR/target/logcollect-benchmark.jar"

JMH_RUN_ARGS=(-f 2 -wi 3 -i 5 -w 1s -r 1s -t 1 -tu ns -bm avgt)
JMH_JVM_ARGS="${JMH_JVM_ARGS:--Xms512m -Xmx512m -XX:+UseG1GC}"
BENCH_FILTER="SecurityPipelineBenchmark\\.pipeline_cleanMessage_emptyMdc|SecurityPipelineBenchmark\\.pipeline_perCall_newInstance|SecurityPipelineBenchmark\\.pipeline_sensitiveMessage_typicalMdc|SecurityPipelineBenchmark\\.pipeline_withThrowable|SanitizeBenchmark\\.sanitize_cleanMessage|SanitizeBenchmark\\.sanitize_withInjection|SanitizeBenchmark\\.sanitize_longMessage|ReflectionVsInterfaceBenchmark\\.reflection_getMethods_everyTime|ReflectionVsInterfaceBenchmark\\.interface_virtualDispatch|ReflectionVsInterfaceBenchmark\\.interface_noop|MdcCopyBenchmark\\.fullCopy_typical|MdcCopyBenchmark\\.lazyCopy_typical_clean|MdcCopyBenchmark\\.fullCopy_large|MdcCopyBenchmark\\.lazyCopy_large_clean"

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
RESULT_JSON="$RESULT_DIR/full-baseline-${JDK_KEY}.json"

cd "$ROOT_DIR"

echo "=== Building benchmark module ==="
mvn -pl logcollect-benchmark -am package -DskipTests -q

echo "=== Running JMH benchmarks for ${JDK_KEY} (${JDK_VERSION}) ==="
"$JAVA_BIN" -jar "$JAR_FILE" \
  "${JMH_RUN_ARGS[@]}" \
  -jvmArgsAppend "$JMH_JVM_ARGS" \
  -rf json -rff "$RESULT_JSON" \
  "$BENCH_FILTER"

echo "=== Updating baseline file (${JDK_KEY}) ==="
python3 "$BENCH_DIR/scripts/update-baseline-json.py" \
  "$RESULT_JSON" \
  "$BASELINE_JSON" \
  --jdk-key "$JDK_KEY" \
  --default-jdk-key "jdk17" \
  --jdk-version "$JDK_VERSION" \
  --java-home "${JAVA_HOME:-unknown}" \
  --jvm-args "$JMH_JVM_ARGS" \
  --jmh-args "-f 2 -wi 3 -i 5 -w 1s -r 1s -t 1 -tu ns -bm avgt"

echo "=== Done. Review and commit ==="
echo "  git diff logcollect-benchmark/src/main/resources/benchmark-baseline.json"
echo "  git add logcollect-benchmark/src/main/resources/benchmark-baseline.json"
echo "  git commit -m 'perf: update benchmark baseline ${JDK_KEY}'"
