#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
RESULT_DIR="$ROOT_DIR/logcollect-benchmark/target/jmh-results"
JAR_FILE="$ROOT_DIR/logcollect-benchmark/target/logcollect-benchmark.jar"

mkdir -p "$RESULT_DIR"

mvn -pl logcollect-benchmark -am package -DskipTests -q

java -jar "$JAR_FILE" \
  -f 2 -wi 3 -i 5 -t 1 \
  -rf json -rff "$RESULT_DIR/full-baseline.json"

echo "JMH full benchmark done: $RESULT_DIR/full-baseline.json"
