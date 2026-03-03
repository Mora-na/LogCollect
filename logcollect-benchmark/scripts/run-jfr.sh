#!/bin/bash
set -euo pipefail

DURATION=${1:-60}
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
OUTPUT_DIR="$ROOT_DIR/logcollect-benchmark/target/profiler"
JAR_FILE="$ROOT_DIR/logcollect-benchmark/target/logcollect-benchmark.jar"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
JFR_FILE="$OUTPUT_DIR/logcollect_${TIMESTAMP}.jfr"

mkdir -p "$OUTPUT_DIR"

mvn -pl logcollect-benchmark -am package -DskipTests -q

java -Xms1g -Xmx1g \
  -XX:+UseG1GC \
  -XX:StartFlightRecording=duration=${DURATION}s,filename=${JFR_FILE},settings=profile \
  -Dspring.profiles.active=stress \
  -cp "$JAR_FILE" \
  com.logcollect.benchmark.profiler.ProfilerApp --scenario=full

echo "JFR file: $JFR_FILE"
