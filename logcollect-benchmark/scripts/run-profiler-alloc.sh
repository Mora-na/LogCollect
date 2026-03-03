#!/bin/bash
set -euo pipefail

DURATION=${1:-30}
PROFILER_HOME=${ASYNC_PROFILER_HOME:-"$HOME/tools/async-profiler"}
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
OUTPUT_DIR="$ROOT_DIR/logcollect-benchmark/target/profiler"
JAR_FILE="$ROOT_DIR/logcollect-benchmark/target/logcollect-benchmark.jar"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

mkdir -p "$OUTPUT_DIR"

mvn -pl logcollect-benchmark -am package -DskipTests -q

java -Xms1g -Xmx1g \
  -XX:+UseG1GC \
  -XX:+UnlockDiagnosticVMOptions \
  -XX:+DebugNonSafepoints \
  -Dspring.profiles.active=stress \
  -cp "$JAR_FILE" \
  com.logcollect.benchmark.profiler.ProfilerApp --scenario=full &

APP_PID=$!
sleep 5

"$PROFILER_HOME/bin/asprof" \
  -d "$DURATION" \
  -e alloc \
  --alloc 1k \
  -f "$OUTPUT_DIR/alloc_flamegraph_${TIMESTAMP}.html" \
  --title "LogCollect Allocation Profile (${DURATION}s)" \
  "$APP_PID"

wait "$APP_PID" 2>/dev/null || true

echo "Allocation flame graph: $OUTPUT_DIR/alloc_flamegraph_${TIMESTAMP}.html"
