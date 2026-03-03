#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
JAR_FILE="$ROOT_DIR/logcollect-benchmark/target/logcollect-benchmark.jar"
JAVA_OPTS=${JAVA_OPTS:-}
PROFILE_OPT="-Dspring.profiles.active=stress"

if [[ " $JAVA_OPTS " == *" -Dspring.profiles.active="* ]]; then
  PROFILE_OPT=""
fi

mvn -pl logcollect-benchmark -am package -DskipTests -q

java $JAVA_OPTS -Xms2g -Xmx2g -XX:+UseG1GC \
  -XX:+DisableExplicitGC \
  -XX:+AlwaysPreTouch \
  -XX:+UnlockDiagnosticVMOptions \
  -XX:+DebugNonSafepoints \
  $PROFILE_OPT \
  -cp "$JAR_FILE" \
  com.logcollect.benchmark.stress.StressTestApp --full
