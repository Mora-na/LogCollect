#!/bin/bash
set -euo pipefail

DURATION=${1:-30}
PROFILER_HOME=${ASYNC_PROFILER_HOME:-"$HOME/tools/async-profiler"}
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
OUTPUT_DIR="$ROOT_DIR/logcollect-benchmark/target/profiler"
JAR_PATH="$ROOT_DIR/logcollect-benchmark/target/logcollect-benchmark.jar"
LOGBACK_XML="$ROOT_DIR/logcollect-benchmark/src/main/resources/logback-benchmark.xml"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
JAVA_OPTS=${JAVA_OPTS:-}
PROFILE_OPT="-Dspring.profiles.active=stress"

if [[ " $JAVA_OPTS " == *" -Dspring.profiles.active="* ]]; then
  PROFILE_OPT=""
fi

# ================================================================
# Pre-flight checks
# ================================================================

echo "=== Pre-flight checks ==="

if [ ! -x "$PROFILER_HOME/bin/asprof" ]; then
  echo "❌ async-profiler not found at $PROFILER_HOME/bin/asprof"
  echo "   Set ASYNC_PROFILER_HOME or download from https://github.com/async-profiler/async-profiler"
  exit 1
fi
echo "✅ async-profiler found"

if [ "$(uname)" = "Linux" ]; then
  PERF_PARANOID=$(cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null || echo "unknown")
  if [ "$PERF_PARANOID" != "unknown" ] && [ "$PERF_PARANOID" -gt 1 ]; then
    echo "⚠️  perf_event_paranoid = $PERF_PARANOID (>1), CPU profiling may be inaccurate"
    echo "   Suggest: sudo sysctl kernel.perf_event_paranoid=1"
  else
    echo "✅ perf_events accessible"
  fi
fi

if [ ! -f "$JAR_PATH" ]; then
  echo "⚠️  Benchmark jar not found, building..."
  mvn -pl logcollect-benchmark -am package -DskipTests -q
fi
echo "✅ Benchmark jar ready"

if [ -f "$LOGBACK_XML" ]; then
  if awk '/<root/{inroot=1} inroot{print} /<\/root>/{if(inroot){exit}}' "$LOGBACK_XML" | grep -q "CONSOLE"; then
    echo "⚠️  WARNING: root logger appears to reference Console appender in logback-benchmark.xml"
    echo "   This may cause lock contention and mask framework hotspots."
  else
    echo "✅ logback-benchmark.xml root logger looks silent"
  fi
fi

if [ "$(uname)" = "Linux" ]; then
  LOAD=$(awk '{print $1}' /proc/loadavg)
  CPUS=$(nproc)
  LOAD_RATIO=$(awk "BEGIN {printf \"%.2f\", $LOAD/$CPUS}")
  if awk "BEGIN {exit !($LOAD_RATIO > 0.70)}"; then
    echo "⚠️  High system load: $LOAD (${LOAD_RATIO}x CPUs). Results may be noisy."
  else
    echo "✅ System load acceptable: $LOAD (${LOAD_RATIO}x CPUs)"
  fi
fi

echo ""

# ================================================================
# Execution
# ================================================================

mkdir -p "$OUTPUT_DIR"

echo "=== Starting stress test app (--full mode, NOP output) ==="
java $JAVA_OPTS -Xms2g -Xmx2g \
  -XX:+UseG1GC \
  -XX:+DisableExplicitGC \
  -XX:+AlwaysPreTouch \
  -XX:+UnlockDiagnosticVMOptions \
  -XX:+DebugNonSafepoints \
  $PROFILE_OPT \
  -cp "$JAR_PATH" \
  com.logcollect.benchmark.stress.StressTestApp --full &

APP_PID=$!
echo "App PID: $APP_PID"

echo "=== Waiting for startup + warmup (8s) ==="
sleep 8

if ! kill -0 "$APP_PID" 2>/dev/null; then
  echo "❌ App exited before profiling started. Check logs."
  exit 1
fi

echo "=== Starting async-profiler CPU sampling (${DURATION}s) ==="
"$PROFILER_HOME/bin/asprof" \
  -d "$DURATION" \
  -e cpu \
  -f "${OUTPUT_DIR}/cpu_flamegraph_${TIMESTAMP}.html" \
  --title "LogCollect CPU Profile (${DURATION}s) - NOP output, no Console I/O" \
  "$APP_PID"

wait "$APP_PID" 2>/dev/null || true

echo ""
echo "=== CPU flame graph saved to ${OUTPUT_DIR}/cpu_flamegraph_${TIMESTAMP}.html ==="
echo "=== Done ==="
