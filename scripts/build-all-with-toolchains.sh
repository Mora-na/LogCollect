#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

usage() {
  cat <<'USAGE'
Usage:
  bash scripts/build-all-with-toolchains.sh
  bash scripts/build-all-with-toolchains.sh -Pwith-samples -DskipTests
  bash scripts/build-all-with-toolchains.sh clean verify -DskipTests

Environment:
  JAVA17_HOME / JAVA_17_HOME / JAVA_HOME_17_X64
  JAVA8_HOME / JAVA_8_HOME / JAVA_HOME_8_X64

Notes:
  - Maven itself must run on JDK 17+ because Spring Boot 3.x Maven plugins are loaded in the Maven JVM.
  - By default this script builds the root project's default reactor only.
  - Add `-Pwith-samples` if you explicitly want to include `logcollect-samples`.
  - When `-Pwith-samples` is enabled, Boot 2.7 sample modules use Maven Toolchains to select JDK 8.
  - When only Maven options are provided, the script appends them to the default `clean verify` command.
USAGE
}

has_explicit_maven_goal() {
  local arg
  local skip_next="false"

  for arg in "$@"; do
    if [ "${skip_next}" = "true" ]; then
      skip_next="false"
      continue
    fi

    case "${arg}" in
      -pl|--projects|-P|--activate-profiles|-f|--file|-rf|--resume-from|-s|--settings|-gs|--global-settings|-t|--toolchains|-l|--log-file|-T|--threads)
        skip_next="true"
        ;;
      -*) ;;
      *)
        return 0
        ;;
    esac
  done

  return 1
}

java_major_from_home() {
  local java_home="$1"
  local version_line version

  if [ ! -x "${java_home}/bin/java" ]; then
    return 1
  fi

  version_line="$(${java_home}/bin/java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
  if [ -z "${version_line}" ]; then
    return 1
  fi

  if [[ "${version_line}" == 1.* ]]; then
    version="${version_line#1.}"
    printf '%s\n' "${version%%.*}"
  else
    printf '%s\n' "${version_line%%.*}"
  fi
}

java_home_at_least() {
  local java_home="$1"
  local minimum_major="$2"
  local actual_major

  actual_major="$(java_major_from_home "${java_home}" 2>/dev/null || true)"
  [ -n "${actual_major}" ] && [ "${actual_major}" -ge "${minimum_major}" ]
}

java_home_matches() {
  local java_home="$1"
  local expected_major="$2"
  local actual_major

  actual_major="$(java_major_from_home "${java_home}" 2>/dev/null || true)"
  [ -n "${actual_major}" ] && [ "${actual_major}" = "${expected_major}" ]
}

resolve_runtime_java_home() {
  local candidate=""
  local candidates=(
    "${JAVA17_HOME:-}"
    "${JAVA_17_HOME:-}"
    "${JAVA_HOME_17_X64:-}"
    "${JAVA_HOME:-}"
    "${JAVA21_HOME:-}"
    "${JAVA_21_HOME:-}"
    "${JAVA_HOME_21_X64:-}"
  )

  for candidate in "${candidates[@]}"; do
    if [ -n "${candidate}" ] && java_home_at_least "${candidate}" 17; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done

  if [ -x "/usr/libexec/java_home" ]; then
    for version in 17 21; do
      candidate="$(/usr/libexec/java_home -v "${version}" 2>/dev/null || true)"
      if [ -n "${candidate}" ] && java_home_at_least "${candidate}" 17; then
        printf '%s\n' "${candidate}"
        return 0
      fi
    done
  fi

  return 1
}

resolve_java8_home() {
  local candidate=""
  local candidates=("${JAVA8_HOME:-}" "${JAVA_8_HOME:-}" "${JAVA_HOME_8_X64:-}")

  for candidate in "${candidates[@]}"; do
    if [ -n "${candidate}" ] && java_home_matches "${candidate}" 8; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done

  if [ -x "/usr/libexec/java_home" ]; then
    candidate="$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)"
    if [ -n "${candidate}" ] && java_home_matches "${candidate}" 8; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  fi

  return 1
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

if ! RUNTIME_JAVA_HOME="$(resolve_runtime_java_home)"; then
  echo "[ERROR] Unable to resolve a JDK 17+ home for the Maven runtime." >&2
  exit 1
fi

if ! JAVA8_TOOLCHAIN_HOME="$(resolve_java8_home)"; then
  echo "[ERROR] Unable to resolve a JDK 8 home for Maven Toolchains." >&2
  exit 1
fi

export JAVA_HOME="${RUNTIME_JAVA_HOME}"
export JAVA17_HOME="${RUNTIME_JAVA_HOME}"
export JAVA8_HOME="${JAVA8_TOOLCHAIN_HOME}"
export PATH="${JAVA_HOME}/bin:${PATH}"

DEFAULT_ARGS=(
  -B
  clean
  verify
  -Dgpg.skip=true
  -DskipPublishing=true
  -DskipDeploy=true
)

if [ "$#" -eq 0 ]; then
  MVN_ARGS=("${DEFAULT_ARGS[@]}")
elif has_explicit_maven_goal "$@"; then
  MVN_ARGS=("$@")
else
  MVN_ARGS=("${DEFAULT_ARGS[@]}" "$@")
fi

echo "Using Maven runtime JAVA_HOME=${JAVA_HOME}"
echo "Using Toolchains JAVA8_HOME=${JAVA8_HOME}"
echo "Working directory: ${REPO_ROOT}"
echo "Command: mvn ${MVN_ARGS[*]}"

cd "${REPO_ROOT}"
exec mvn "${MVN_ARGS[@]}"
