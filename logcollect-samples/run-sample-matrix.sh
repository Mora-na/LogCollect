#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOG_DIR="${REPO_ROOT}/logs/logcollect-samples"
SUMMARY_FILE="${LOG_DIR}/sample-batch-summary.txt"
REPORT_SCRIPT="${REPO_ROOT}/scripts/render_sample_matrix_report.py"
REPORT_MD="${LOG_DIR}/sample-matrix-report.md"
REPORT_JSON="${LOG_DIR}/sample-matrix-report.json"

SAMPLES=(
  "logcollect-sample-boot27-logback|8|2.7.18|logback"
  "logcollect-sample-boot27-log4j2|8|2.7.18|log4j2"
  "logcollect-sample-boot30-logback|17|3.0.13|logback"
  "logcollect-sample-boot30-log4j2|17|3.0.13|log4j2"
  "logcollect-sample-boot32-logback|17|3.2.5|logback"
  "logcollect-sample-boot32-log4j2|17|3.2.5|log4j2"
  "logcollect-sample-boot34-logback|17|3.4.1|logback"
  "logcollect-sample-boot34-log4j2|17|3.4.1|log4j2"
)

SELECTED_MODULE=""
LIST_ONLY="false"
FAILURES=()
BOOTSTRAP_DONE="false"

usage() {
  cat <<'USAGE'
Usage:
  bash logcollect-samples/run-sample-matrix.sh
  bash logcollect-samples/run-sample-matrix.sh --module logcollect-sample-boot34-log4j2
  bash logcollect-samples/run-sample-matrix.sh --list

Options:
  --module <artifactId>  Run only one sample module.
  --list                 Print supported sample modules and exit.
  -h, --help             Show this help message.

Environment:
  JAVA_8_HOME / JAVA8_HOME / JAVA_HOME_8_X64
  JAVA_17_HOME / JAVA17_HOME / JAVA_HOME_17_X64

Notes:
  - On macOS, the script can auto-resolve local JDKs via /usr/libexec/java_home.
  - On Linux, set JAVA_8_HOME and JAVA_17_HOME when running all 8 modules locally.
USAGE
}

print_samples() {
  local entry module java_version boot_version log_framework
  for entry in "${SAMPLES[@]}"; do
    IFS='|' read -r module java_version boot_version log_framework <<EOF_ENTRY
${entry}
EOF_ENTRY
    printf '%s\tJDK %s\tBoot %s\t%s\n' "${module}" "${java_version}" "${boot_version}" "${log_framework}"
  done
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

java_home_matches() {
  local java_home="$1"
  local expected_major="$2"
  local actual_major

  actual_major="$(java_major_from_home "${java_home}" 2>/dev/null || true)"
  [ -n "${actual_major}" ] && [ "${actual_major}" = "${expected_major}" ]
}

resolve_java_home() {
  local expected_major="$1"
  local candidate=""
  local candidates=()

  if [ -n "${JAVA_HOME:-}" ] && java_home_matches "${JAVA_HOME}" "${expected_major}"; then
    printf '%s\n' "${JAVA_HOME}"
    return 0
  fi

  if [ "${expected_major}" = "8" ]; then
    candidates=("${JAVA_8_HOME:-}" "${JAVA8_HOME:-}" "${JAVA_HOME_8_X64:-}")
  else
    candidates=("${JAVA_17_HOME:-}" "${JAVA17_HOME:-}" "${JAVA_HOME_17_X64:-}")
  fi

  for candidate in "${candidates[@]}"; do
    if [ -n "${candidate}" ] && java_home_matches "${candidate}" "${expected_major}"; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done

  if [ -x "/usr/libexec/java_home" ]; then
    if [ "${expected_major}" = "8" ]; then
      candidate="$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)"
    else
      candidate="$(/usr/libexec/java_home -v ${expected_major} 2>/dev/null || true)"
    fi
    if [ -n "${candidate}" ] && java_home_matches "${candidate}" "${expected_major}"; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  fi

  return 1
}

find_entry_by_module() {
  local target_module="$1"
  local entry module java_version boot_version log_framework

  for entry in "${SAMPLES[@]}"; do
    IFS='|' read -r module java_version boot_version log_framework <<EOF_ENTRY
${entry}
EOF_ENTRY
    if [ "${module}" = "${target_module}" ]; then
      printf '%s\n' "${entry}"
      return 0
    fi
  done

  return 1
}

resolve_bootstrap_java_home() {
  local java_home=""

  java_home="$(resolve_java_home 17 2>/dev/null || true)"
  if [ -n "${java_home}" ]; then
    printf '%s\n' "${java_home}"
    return 0
  fi

  java_home="$(resolve_java_home 8 2>/dev/null || true)"
  if [ -n "${java_home}" ]; then
    printf '%s\n' "${java_home}"
    return 0
  fi

  return 1
}

install_workspace_artifacts() {
  local java_home="$1"

  if [ "${BOOTSTRAP_DONE}" = "true" ]; then
    return 0
  fi

  echo "[BOOTSTRAP] Installing current workspace BOM and starter artifacts for sample dependencies" | tee -a "${SUMMARY_FILE}"
  if (
    export JAVA_HOME="${java_home}"
    export PATH="${JAVA_HOME}/bin:${PATH}"
    cd "${REPO_ROOT}"
    mvn -B \
      -pl "logcollect-bom,logcollect-spring-boot-starter" \
      -am \
      clean \
      -DskipTests \
      -Dgpg.skip=true \
      -DskipPublishing=true \
      -DskipDeploy=true \
      -Djacoco.skip=true \
      -Dmaven.javadoc.skip=true \
      -Dmaven.source.skip=true \
      install
  ); then
    BOOTSTRAP_DONE="true"
    echo "[BOOTSTRAP] Workspace artifacts are ready." | tee -a "${SUMMARY_FILE}"
    echo | tee -a "${SUMMARY_FILE}"
    return 0
  fi

  echo "[BOOTSTRAP] Failed to install current workspace artifacts." | tee -a "${SUMMARY_FILE}"
  FAILURES+=("sample-bootstrap")
  return 1
}

resolve_app_log() {
  local module="$1"
  local module_log_dir="${REPO_ROOT}/logcollect-samples/${module}/logs/logcollect-samples"

  if [ ! -d "${module_log_dir}" ]; then
    return 1
  fi

  if [ -f "${module_log_dir}/${module}.log" ]; then
    printf '%s\n' "${module_log_dir}/${module}.log"
    return 0
  fi

  find "${module_log_dir}" -maxdepth 1 -type f -name '*.log' | sort | tail -n 1
}

run_sample() {
  local entry="$1"
  local module java_version boot_version log_framework
  local java_home module_pom console_log app_log status_line module_log_dir

  IFS='|' read -r module java_version boot_version log_framework <<EOF_ENTRY
${entry}
EOF_ENTRY

  if ! java_home="$(resolve_java_home "${java_version}")"; then
    echo "[ERROR] Unable to resolve JDK ${java_version} for ${module}." | tee -a "${SUMMARY_FILE}"
    echo "[ERROR] Set JAVA_${java_version}_HOME or JAVA_HOME_${java_version}_X64 before retrying." | tee -a "${SUMMARY_FILE}"
    FAILURES+=("${module}")
    return 0
  fi

  module_pom="${REPO_ROOT}/logcollect-samples/${module}/pom.xml"
  console_log="${LOG_DIR}/${module}.console.log"
  module_log_dir="${REPO_ROOT}/logcollect-samples/${module}/logs/logcollect-samples"

  rm -f "${console_log}"
  find "${module_log_dir}" -maxdepth 1 -type f -name '*.log' -delete 2>/dev/null || true

  status_line="[$(date '+%Y-%m-%d %H:%M:%S')] START ${module} | JDK ${java_version} | Boot ${boot_version} | ${log_framework}"
  echo "${status_line}" | tee -a "${SUMMARY_FILE}"

  if (
    export JAVA_HOME="${java_home}"
    export PATH="${JAVA_HOME}/bin:${PATH}"
    cd "${REPO_ROOT}"
    echo "[RUN] Starting sample module ${module}"
    mvn -B \
      -f "${module_pom}" \
      -am \
      -DskipTests \
      -Dgpg.skip=true \
      -DskipPublishing=true \
      -DskipDeploy=true \
      -Djacoco.skip=true \
      "org.springframework.boot:spring-boot-maven-plugin:${boot_version}:run" \
      -Dspring-boot.run.arguments=--sample.exit-after-run=true
  ) 2>&1 | tee "${console_log}"; then
    app_log="$(resolve_app_log "${module}" || true)"
    if [ -n "${app_log}" ]; then
      echo "[$(date '+%Y-%m-%d %H:%M:%S')] SUCCESS ${module} | console=${console_log} | app=${app_log}" | tee -a "${SUMMARY_FILE}"
    else
      echo "[$(date '+%Y-%m-%d %H:%M:%S')] SUCCESS ${module} | console=${console_log} | app=missing" | tee -a "${SUMMARY_FILE}"
    fi
  else
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] FAILURE ${module} | console=${console_log}" | tee -a "${SUMMARY_FILE}"
    FAILURES+=("${module}")
  fi

  echo | tee -a "${SUMMARY_FILE}"
}

render_report() {
  if [ ! -f "${REPORT_SCRIPT}" ] || ! command -v python3 >/dev/null 2>&1; then
    return 0
  fi

  if ! python3 "${REPORT_SCRIPT}" \
    --input-root "${LOG_DIR}" \
    --output-md "${REPORT_MD}" \
    --output-json "${REPORT_JSON}" \
    --fail-on-issues \
    >/dev/null; then
    FAILURES+=("sample-report-validation")
  fi
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --module)
      if [ "$#" -lt 2 ]; then
        echo "[ERROR] --module requires a value." >&2
        usage
        exit 1
      fi
      SELECTED_MODULE="$2"
      shift 2
      ;;
    --list)
      LIST_ONLY="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[ERROR] Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [ "${LIST_ONLY}" = "true" ]; then
  print_samples
  exit 0
fi

mkdir -p "${LOG_DIR}"
: > "${SUMMARY_FILE}"

echo "Sample batch started at $(date '+%Y-%m-%d %H:%M:%S')" | tee -a "${SUMMARY_FILE}"
echo "Logs directory: ${LOG_DIR}" | tee -a "${SUMMARY_FILE}"
echo | tee -a "${SUMMARY_FILE}"

if ! BOOTSTRAP_JAVA_HOME="$(resolve_bootstrap_java_home)"; then
  echo "[ERROR] Unable to resolve a JDK for workspace bootstrap." | tee -a "${SUMMARY_FILE}"
  echo "[ERROR] Install JDK 8 or JDK 17, or set JAVA_8_HOME / JAVA_17_HOME before retrying." | tee -a "${SUMMARY_FILE}"
  exit 1
fi

if ! install_workspace_artifacts "${BOOTSTRAP_JAVA_HOME}"; then
  exit 1
fi

if [ -n "${SELECTED_MODULE}" ]; then
  if ! ENTRY="$(find_entry_by_module "${SELECTED_MODULE}")"; then
    echo "[ERROR] Unknown module: ${SELECTED_MODULE}" >&2
    echo >&2
    print_samples >&2
    exit 1
  fi
  run_sample "${ENTRY}"
else
  for ENTRY in "${SAMPLES[@]}"; do
    run_sample "${ENTRY}"
  done
fi

render_report
if [ -f "${REPORT_MD}" ]; then
  echo "Markdown report: ${REPORT_MD}" | tee -a "${SUMMARY_FILE}"
fi
if [ -f "${REPORT_JSON}" ]; then
  echo "JSON report: ${REPORT_JSON}" | tee -a "${SUMMARY_FILE}"
fi
echo | tee -a "${SUMMARY_FILE}"

if [ "${#FAILURES[@]}" -gt 0 ]; then
  echo "Failed modules: ${FAILURES[*]}" | tee -a "${SUMMARY_FILE}"
  exit 1
fi

echo "All sample modules completed successfully." | tee -a "${SUMMARY_FILE}"
