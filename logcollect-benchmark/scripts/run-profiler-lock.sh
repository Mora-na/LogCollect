#!/bin/bash
set -euo pipefail

DURATION=${1:-30}
PROFILER_HOME=${ASYNC_PROFILER_HOME:-"$HOME/tools/async-profiler"}
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
OUTPUT_DIR="$ROOT_DIR/logcollect-benchmark/target/profiler"
JAR_FILE="$ROOT_DIR/logcollect-benchmark/target/logcollect-benchmark.jar"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
JAVA_OPTS=${JAVA_OPTS:-}
PROFILE_OPT="-Dspring.profiles.active=stress"

if [[ " $JAVA_OPTS " == *" -Dspring.profiles.active="* ]]; then
  PROFILE_OPT=""
fi

mkdir -p "$OUTPUT_DIR"

mvn -pl logcollect-benchmark -am package -DskipTests -q

java $JAVA_OPTS -Xms1g -Xmx1g \
  -XX:+UseG1GC \
  -XX:+DisableExplicitGC \
  -XX:+AlwaysPreTouch \
  $PROFILE_OPT \
  -cp "$JAR_FILE" \
  com.logcollect.benchmark.profiler.ProfilerApp --scenario=full &

APP_PID=$!
sleep 5

"$PROFILER_HOME/bin/asprof" \
  -d "$DURATION" \
  -e lock \
  --lock 1ms \
  -f "$OUTPUT_DIR/lock_flamegraph_${TIMESTAMP}.html" \
  --title "LogCollect Lock Contention Profile (${DURATION}s)" \
  "$APP_PID"

wait "$APP_PID" 2>/dev/null || true

echo "Lock flame graph: $OUTPUT_DIR/lock_flamegraph_${TIMESTAMP}.html"
