#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
RESULT_DIR="$ROOT_DIR/logcollect-benchmark/target/jmh-results"
JAR_FILE="$ROOT_DIR/logcollect-benchmark/target/logcollect-benchmark.jar"

mkdir -p "$RESULT_DIR"

mvn -pl logcollect-benchmark -am package -DskipTests -q

java -jar "$JAR_FILE" \
  -f 2 -wi 5 -i 10 -t 1 \
  -tu ns -bm avgt \
  -rf json -rff "$RESULT_DIR/full-baseline.json" \
  "SecurityPipelineBenchmark|SanitizeBenchmark|MaskBenchmark|ReflectionVsInterfaceBenchmark|MdcCopyBenchmark|TimestampFormatBenchmark"

echo "JMH full benchmark done: $RESULT_DIR/full-baseline.json"
