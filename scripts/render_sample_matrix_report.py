#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, asdict
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional

EXPECTED_SCENARIO_STARTS = 15
EXPECTED_SCENARIO_COMPLETIONS = 15
EXPECTED_SCENARIO_FLUSHES = 16
EXPECTED_SCENARIO_VALIDATIONS = 16
START_RE = re.compile(
    r"^\[(?P<timestamp>[^\]]+)\] START (?P<module>\S+) \| JDK (?P<java>[^|]+) \| Boot (?P<boot>[^|]+) \| (?P<framework>.+)$"
)
RESULT_RE = re.compile(
    r"^\[(?P<timestamp>[^\]]+)\] (?P<status>SUCCESS|FAILURE) (?P<module>\S+) \| (?P<details>.+)$"
)
VALIDATION_RE = re.compile(
    r"^场景校验: (?P<scenario>.+), expected=(?P<expected>[^,]+), "
    r"collected=(?P<collected>\d+), discarded=(?P<discarded>\d+), "
    r"flushCount=(?P<flush_count>\d+), status=(?P<status>PASS|FAIL)$"
)


@dataclass
class ModuleReport:
    module: str
    java: str = ""
    boot: str = ""
    framework: str = ""
    status: str = "UNKNOWN"
    summary_path: str = ""
    console_log: str = ""
    app_log: str = ""
    scenario_starts: int = 0
    scenario_completions: int = 0
    scenario_flushes: int = 0
    scenario_validations: int = 0
    scenario_validation_failures: int = 0
    notes: List[str] = None

    def __post_init__(self) -> None:
        if self.notes is None:
            self.notes = []

    @property
    def normalized_status(self) -> str:
        if self.status != "SUCCESS":
            return "FAIL"
        if self.scenario_validation_failures > 0:
            return "FAIL"
        if (
            self.scenario_starts != EXPECTED_SCENARIO_STARTS
            or self.scenario_completions != EXPECTED_SCENARIO_COMPLETIONS
            or self.scenario_flushes != EXPECTED_SCENARIO_FLUSHES
            or self.scenario_validations != EXPECTED_SCENARIO_VALIDATIONS
        ):
            return "WARN"
        return "PASS"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Render a sample-matrix markdown report.")
    parser.add_argument(
        "--input-root",
        type=Path,
        required=True,
        help="Root directory containing sample-batch-summary.txt and console logs.",
    )
    parser.add_argument(
        "--output-md",
        type=Path,
        required=True,
        help="Markdown report output path.",
    )
    parser.add_argument(
        "--output-json",
        type=Path,
        default=None,
        help="Optional JSON output path.",
    )
    parser.add_argument(
        "--fail-on-issues",
        action="store_true",
        help="Exit with code 1 when failures or warnings are detected.",
    )
    return parser.parse_args()


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="ignore")


def find_log(root: Path, module: str, suffix: str) -> Optional[Path]:
    direct = root / f"{module}{suffix}"
    if direct.exists():
        return direct
    matches = sorted(root.rglob(f"{module}{suffix}"))
    return matches[0] if matches else None


def find_app_log(root: Path, module: str) -> Optional[Path]:
    direct = find_log(root, module, ".log")
    if direct is not None:
        return direct

    candidates = []
    for path in root.rglob("*.log"):
        if path.name.endswith(".console.log"):
            continue
        if module not in path.parts:
            continue
        candidates.append(path)

    return sorted(candidates)[0] if candidates else None


def count_markers(path: Optional[Path], marker: str) -> int:
    if path is None or not path.exists():
        return 0
    return read_text(path).count(marker)


def parse_validations(path: Optional[Path]) -> tuple[int, int]:
    if path is None or not path.exists():
        return 0, 0
    validations = 0
    failures = 0
    for raw_line in read_text(path).splitlines():
        line = raw_line.strip()
        match = VALIDATION_RE.match(line)
        if not match:
            continue
        validations += 1
        if match.group("status") != "PASS":
            failures += 1
    return validations, failures


def collect_reports(input_root: Path) -> Dict[str, ModuleReport]:
    reports: Dict[str, ModuleReport] = {}

    for summary_path in sorted(input_root.rglob("sample-batch-summary.txt")):
        for raw_line in read_text(summary_path).splitlines():
            line = raw_line.strip()
            if not line:
                continue

            start_match = START_RE.match(line)
            if start_match:
                module = start_match.group("module")
                report = reports.get(module, ModuleReport(module=module))
                report.java = start_match.group("java").strip()
                report.boot = start_match.group("boot").strip()
                report.framework = start_match.group("framework").strip()
                report.summary_path = str(summary_path)
                reports[module] = report
                continue

            result_match = RESULT_RE.match(line)
            if result_match:
                module = result_match.group("module")
                report = reports.get(module, ModuleReport(module=module))
                report.status = result_match.group("status")
                report.summary_path = str(summary_path)
                details = result_match.group("details")
                for part in details.split(" | "):
                    if part.startswith("console="):
                        report.console_log = part[len("console="):]
                    if part.startswith("app="):
                        report.app_log = part[len("app="):]
                reports[module] = report

    for module, report in reports.items():
        console_path = find_log(input_root, module, ".console.log")
        app_path = find_app_log(input_root, module)

        if console_path is not None:
            report.console_log = str(console_path)
            report.scenario_starts = count_markers(console_path, "开始执行 场景")
            report.scenario_completions = count_markers(console_path, "执行完成 场景")
            report.scenario_flushes = count_markers(console_path, "场景收尾:")
            report.scenario_validations, report.scenario_validation_failures = parse_validations(console_path)
        else:
            report.notes.append("console log missing")

        if app_path is not None:
            report.app_log = str(app_path)
        elif report.app_log:
            if not Path(report.app_log).exists():
                report.notes.append("app log missing")
        else:
            report.notes.append("app log missing")

        if report.status != "SUCCESS":
            report.notes.append("module execution failed or summary incomplete")
        if report.scenario_starts and report.scenario_starts != EXPECTED_SCENARIO_STARTS:
            report.notes.append(f"scenario starts={report.scenario_starts}, expected {EXPECTED_SCENARIO_STARTS}")
        if report.scenario_completions and report.scenario_completions != EXPECTED_SCENARIO_COMPLETIONS:
            report.notes.append(
                f"scenario completions={report.scenario_completions}, expected {EXPECTED_SCENARIO_COMPLETIONS}"
            )
        if report.scenario_flushes and report.scenario_flushes != EXPECTED_SCENARIO_FLUSHES:
            report.notes.append(
                f"scenario flushes={report.scenario_flushes}, expected {EXPECTED_SCENARIO_FLUSHES}"
            )
        if report.scenario_validations and report.scenario_validations != EXPECTED_SCENARIO_VALIDATIONS:
            report.notes.append(
                f"scenario validations={report.scenario_validations}, expected {EXPECTED_SCENARIO_VALIDATIONS}"
            )
        if report.scenario_validation_failures > 0:
            report.notes.append(f"scenario validation failures={report.scenario_validation_failures}")

    return reports


def build_markdown(reports: List[ModuleReport]) -> str:
    total = len(reports)
    passed = sum(1 for report in reports if report.normalized_status == "PASS")
    warned = sum(1 for report in reports if report.normalized_status == "WARN")
    failed = sum(1 for report in reports if report.normalized_status == "FAIL")

    lines = [
        "# Sample Matrix Report",
        "",
        f"- Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        f"- Modules: {total}",
        f"- PASS: {passed}",
        f"- WARN: {warned}",
        f"- FAIL: {failed}",
        f"- Expected per module: starts={EXPECTED_SCENARIO_STARTS}, completions={EXPECTED_SCENARIO_COMPLETIONS}, "
        f"flushes={EXPECTED_SCENARIO_FLUSHES}, validations={EXPECTED_SCENARIO_VALIDATIONS}",
        "",
        "| Module | JDK | Boot | Logger | Status | Starts | Completions | Flushes | Validations | Validation Failures | Notes |",
        "| --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |",
    ]

    for report in reports:
        notes = "; ".join(report.notes) if report.notes else "-"
        lines.append(
            f"| {report.module} | {report.java or '-'} | {report.boot or '-'} | {report.framework or '-'} | "
            f"{report.normalized_status} | {report.scenario_starts} | {report.scenario_completions} | "
            f"{report.scenario_flushes} | {report.scenario_validations} | {report.scenario_validation_failures} | {notes} |"
        )

    return "\n".join(lines) + "\n"


def main() -> int:
    args = parse_args()
    reports_map = collect_reports(args.input_root)
    reports = sorted(reports_map.values(), key=lambda item: item.module)

    if not reports:
        print("No sample-batch-summary.txt files found.", file=sys.stderr)
        return 1

    markdown = build_markdown(reports)
    args.output_md.parent.mkdir(parents=True, exist_ok=True)
    args.output_md.write_text(markdown, encoding="utf-8")

    if args.output_json is not None:
        args.output_json.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "generated_at": datetime.now().isoformat(timespec="seconds"),
            "expected": {
                "starts": EXPECTED_SCENARIO_STARTS,
                "completions": EXPECTED_SCENARIO_COMPLETIONS,
                "flushes": EXPECTED_SCENARIO_FLUSHES,
                "validations": EXPECTED_SCENARIO_VALIDATIONS,
            },
            "reports": [asdict(report) | {"normalized_status": report.normalized_status} for report in reports],
        }
        args.output_json.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    sys.stdout.write(markdown)

    if args.fail_on_issues and any(report.normalized_status != "PASS" for report in reports):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
