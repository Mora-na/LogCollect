#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
JAR_FILE="$ROOT_DIR/logcollect-benchmark/target/logcollect-benchmark.jar"

mvn -pl logcollect-benchmark -am package -DskipTests -q

java -Xms2g -Xmx2g -XX:+UseG1GC \
  -Dspring.profiles.active=stress \
  -cp "$JAR_FILE" \
  com.logcollect.benchmark.stress.StressTestApp --full
