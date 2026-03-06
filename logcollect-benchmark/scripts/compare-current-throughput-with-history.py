#!/usr/bin/env python3
"""
Compare current local throughput run with all historical local throughput baselines.

Data sources:
1) Current run JSON: BENCHMARK_RESULTS_JSON extracted from StressTestApp logs
2) Git history snapshots of benchmark-baseline.json (top-level ratios.stressGate.throughputBaselines)

Usage:
  python3 compare-current-throughput-with-history.py \
    --current-json logcollect-benchmark/target/stress-results/current-throughput.json \
    --baseline-file logcollect-benchmark/src/main/resources/benchmark-baseline.json \
    --output logcollect-benchmark/target/stress-results/current-vs-history-throughput.md
"""

import argparse
import json
import subprocess
from datetime import datetime
from pathlib import Path

SCENARIOS = [
    "baseline-1t-nop",
    "isolated-1t-clean",
    "isolated-8t-clean",
    "isolated-8t-sensitive",
    "e2e-1t-clean",
    "e2e-8t-clean",
    "e2e-8t-sensitive",
]

HISTORY_SCENARIOS = [
    "isolated-1t-clean",
    "isolated-8t-clean",
    "isolated-8t-sensitive",
    "e2e-1t-clean",
    "e2e-8t-clean",
    "e2e-8t-sensitive",
]


def parse_args():
    parser = argparse.ArgumentParser(
        description="Compare current throughput with all historical local throughput baselines."
    )
    parser.add_argument(
        "--current-json",
        required=True,
        help="Current BENCHMARK_RESULTS_JSON file path.",
    )
    parser.add_argument(
        "--baseline-file",
        default="logcollect-benchmark/src/main/resources/benchmark-baseline.json",
        help="Path to benchmark-baseline.json in repo.",
    )
    parser.add_argument(
        "--output",
        default="logcollect-benchmark/target/stress-results/current-vs-history-throughput.md",
        help="Output markdown file path.",
    )
    return parser.parse_args()


def run_git(args):
    process = subprocess.run(
        ["git"] + args,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    if process.returncode != 0:
        raise RuntimeError(
            "git command failed: git {}\n{}".format(" ".join(args), process.stderr.strip())
        )
    return process.stdout


def repo_rel_path(path_text):
    repo_root = Path(run_git(["rev-parse", "--show-toplevel"]).strip())
    abs_path = Path(path_text).resolve()
    return repo_root, abs_path.relative_to(repo_root).as_posix()


def load_current(current_json):
    data = json.loads(Path(current_json).read_text(encoding="utf-8"))
    row = {}
    for scenario in SCENARIOS:
        node = data.get(scenario, {}) if isinstance(data, dict) else {}
        value = node.get("throughput") if isinstance(node, dict) else None
        row[scenario] = float(value) if isinstance(value, (int, float)) else None
    return row


def list_baseline_commits(rel_baseline_path):
    text = run_git(
        [
            "log",
            "--reverse",
            "--pretty=format:%H\t%cI\t%an\t%s",
            "--",
            rel_baseline_path,
        ]
    )
    commits = []
    for line in text.splitlines():
        parts = line.split("\t", 3)
        if len(parts) != 4:
            continue
        commits.append(
            {
                "hash": parts[0],
                "time": parts[1],
                "author": parts[2],
                "subject": parts[3],
            }
        )
    return commits


def load_json_at_commit(commit_hash, rel_baseline_path):
    content = run_git(["show", "{}:{}".format(commit_hash, rel_baseline_path)])
    content = content.strip()
    if not content or content == "[]":
        return None
    parsed = json.loads(content)
    return parsed if isinstance(parsed, dict) else None


def extract_local_throughput(snapshot):
    ratios = snapshot.get("ratios", {}) if isinstance(snapshot, dict) else {}
    gate = ratios.get("stressGate", {}) if isinstance(ratios, dict) else {}
    tb = gate.get("throughputBaselines", {}) if isinstance(gate, dict) else {}
    if not isinstance(tb, dict) or not tb:
        return None
    result = {}
    for scenario in HISTORY_SCENARIOS:
        value = tb.get(scenario)
        result[scenario] = float(value) if isinstance(value, (int, float)) else None
    if any(v is not None for v in result.values()):
        return result
    return None


def fmt_num(value):
    if value is None:
        return "-"
    return "{:,.1f}".format(value)


def fmt_pct(value):
    if value is None:
        return "-"
    return "{:+.2f}%".format(value)


def percent_delta(base, current):
    if base is None or current is None or current == 0:
        return None
    return (base / current - 1.0) * 100.0


def build_markdown(current_row, history_rows):
    lines = []
    lines.append("# Current vs History Throughput Comparison (local machine)")
    lines.append("")
    lines.append("## Throughput (logs/s)")
    lines.append("")

    headers = ["Version", "CommitTime", "Source"] + SCENARIOS
    lines.append("| " + " | ".join(headers) + " |")
    lines.append("| " + " | ".join(["---", "---", "---"] + ["---:"] * len(SCENARIOS)) + " |")

    current_cells = [
        "current-run",
        datetime.now().isoformat(timespec="seconds"),
        "local-stress-smoke",
    ]
    current_cells.extend(fmt_num(current_row.get(s)) for s in SCENARIOS)
    lines.append("| " + " | ".join(current_cells) + " |")

    for row in history_rows:
        cells = [
            row["short_hash"],
            row["time"],
            "history-local-stressGate",
            "-",  # baseline-1t-nop not present in historical local throughputBaselines
        ]
        cells.extend(fmt_num(row["throughput"].get(s)) for s in HISTORY_SCENARIOS)
        lines.append("| " + " | ".join(cells) + " |")

    lines.append("")
    lines.append("## Delta vs Current (%)")
    lines.append("")
    lines.append("Computed as: `(history/current - 1) * 100`")
    lines.append("")
    delta_headers = ["Version", "CommitTime"] + HISTORY_SCENARIOS
    lines.append("| " + " | ".join(delta_headers) + " |")
    lines.append("| " + " | ".join(["---", "---"] + ["---:"] * len(HISTORY_SCENARIOS)) + " |")

    for row in history_rows:
        cells = [row["short_hash"], row["time"]]
        for s in HISTORY_SCENARIOS:
            d = percent_delta(row["throughput"].get(s), current_row.get(s))
            cells.append(fmt_pct(d))
        lines.append("| " + " | ".join(cells) + " |")

    lines.append("")
    return "\n".join(lines)


def main():
    args = parse_args()

    _, rel_baseline_path = repo_rel_path(args.baseline_file)
    current_row = load_current(args.current_json)
    commits = list_baseline_commits(rel_baseline_path)

    history_rows = []
    for c in commits:
        snapshot = load_json_at_commit(c["hash"], rel_baseline_path)
        if snapshot is None:
            continue
        throughput = extract_local_throughput(snapshot)
        if throughput is None:
            continue
        history_rows.append(
            {
                "hash": c["hash"],
                "short_hash": c["hash"][:7],
                "time": c["time"],
                "author": c["author"],
                "subject": c["subject"],
                "throughput": throughput,
            }
        )

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    md = build_markdown(current_row, history_rows)
    output_path.write_text(md, encoding="utf-8")

    print("Current run loaded:", Path(args.current_json).resolve())
    print("History versions with local throughput:", len(history_rows))
    print("Output:", output_path.resolve())
    print("")
    print(md)


if __name__ == "__main__":
    main()
