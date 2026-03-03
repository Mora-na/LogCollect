#!/bin/bash
# Run full JMH and refresh benchmark-baseline.json.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
BENCH_DIR="$ROOT_DIR/logcollect-benchmark"
RESULT_DIR="$BENCH_DIR/target/jmh-results"
RESULT_JSON="$RESULT_DIR/full-baseline.json"
BASELINE_JSON="$BENCH_DIR/src/main/resources/benchmark-baseline.json"

mkdir -p "$RESULT_DIR"

cd "$ROOT_DIR"

echo "=== Building benchmark module ==="
mvn -pl logcollect-benchmark -am package -DskipTests -q

echo "=== Running full JMH benchmarks ==="
java -jar "$BENCH_DIR/target/logcollect-benchmark.jar" \
  -f 2 -wi 5 -i 10 -t 1 \
  -tu ns -bm avgt \
  -rf json -rff "$RESULT_JSON" \
  "SecurityPipelineBenchmark|SanitizeBenchmark|MaskBenchmark|ReflectionVsInterfaceBenchmark|MdcCopyBenchmark|TimestampFormatBenchmark"

echo "=== Updating baseline file ==="
python3 "$BENCH_DIR/scripts/update-baseline-json.py" \
  "$RESULT_JSON" \
  "$BASELINE_JSON"

echo "=== Done. Review and commit ==="
echo "  git diff logcollect-benchmark/src/main/resources/benchmark-baseline.json"
echo "  git add logcollect-benchmark/src/main/resources/benchmark-baseline.json"
echo "  git commit -m 'perf: update benchmark baseline'"
