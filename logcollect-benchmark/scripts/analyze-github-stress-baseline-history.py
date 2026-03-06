#!/usr/bin/env python3
"""
Analyze GitHub CI stress baseline history from benchmark-baseline.json.

Default target:
  ciStressBaselines.github-ubuntu-latest.[jdk8|jdk11|jdk17|jdk21].stressGate

Outputs three console tables:
  1) throughputBaselines (higher is better)
  2) ratioBaselines      (higher is better)
  3) gcBaselines         (lower is better)
"""

import argparse
import json
import subprocess
import sys
from datetime import datetime
from pathlib import Path

JDK_ORDER = ["jdk8", "jdk11", "jdk17", "jdk21"]
JDK_LABEL = {
    "jdk8": "JDK1.8",
    "jdk11": "JDK11",
    "jdk17": "JDK17",
    "jdk21": "JDK21",
}

METRICS = [
    {
        "name": "throughputBaselines",
        "title": "吞吐维度（越高越好）",
        "higher_better": True,
        "digits": 1,
    },
    {
        "name": "ratioBaselines",
        "title": "扩展比维度（越高越好）",
        "higher_better": True,
        "digits": 3,
    },
    {
        "name": "gcBaselines",
        "title": "GC 维度（越低越好）",
        "higher_better": False,
        "digits": 2,
    },
]

METRIC_ALIASES = {
    "throughputBaselines": ["throughputBaselines", "throughputBaseLines"],
    "ratioBaselines": ["ratioBaselines", "ratioBaseLines"],
    "gcBaselines": ["gcBaselines", "gcBaseLines"],
}


def parse_args():
    parser = argparse.ArgumentParser(
        description="分析 GitHub 提交中的 stress 基线历史并输出三维度表格。"
    )
    parser.add_argument(
        "--baseline-file",
        default="logcollect-benchmark/src/main/resources/benchmark-baseline.json",
        help="基线文件路径（默认: %(default)s）",
    )
    parser.add_argument(
        "--profile",
        default="github-ubuntu-latest",
        help="ciStressBaselines profile（默认: %(default)s）",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=6,
        help="读取最近 N 个提交版本（默认: %(default)s）",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help="读取全部历史提交版本（忽略 --limit）",
    )
    parser.add_argument(
        "--include-non-github",
        action="store_true",
        help="包含非 github-actions 提交（默认只分析 GitHub 提交）",
    )
    parser.add_argument(
        "--stable-threshold",
        type=float,
        default=0.03,
        help="结论判定持平阈值（默认: 0.03，表示 3%%）",
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


def resolve_git_path(path_text):
    repo_root = Path(run_git(["rev-parse", "--show-toplevel"]).strip())
    input_path = Path(path_text)
    if input_path.is_absolute():
        abs_path = input_path
    else:
        abs_path = (Path.cwd() / input_path).resolve()

    try:
        rel_path = abs_path.relative_to(repo_root.resolve()).as_posix()
    except ValueError:
        rel_path = path_text.replace("\\", "/")
    return repo_root, rel_path


def is_github_commit(author, subject):
    author_l = (author or "").lower()
    subject_l = (subject or "").lower()
    return (
        "github-actions" in author_l
        or "refresh stress ci baseline" in subject_l
        or "github[bot]" in author_l
    )


def list_target_commits(git_path, limit, all_commits, include_non_github):
    log_text = run_git(["log", "--pretty=format:%H\t%cI\t%an\t%s", "--", git_path])
    commits = []
    for line in log_text.splitlines():
        parts = line.split("\t", 3)
        if len(parts) != 4:
            continue
        commits.append(
            {
                "hash": parts[0],
                "commit_time": parts[1],
                "author": parts[2],
                "subject": parts[3],
            }
        )

    if not commits:
        raise RuntimeError("没有找到基线文件历史提交: {}".format(git_path))

    if not include_non_github:
        filtered = [c for c in commits if is_github_commit(c["author"], c["subject"])]
        if filtered:
            commits = filtered
        else:
            print(
                "[WARN] 未匹配到 GitHub 提交，回退为分析全部提交。",
                file=sys.stderr,
            )

    if not all_commits:
        commits = commits[: max(1, limit)]

    commits.reverse()  # old -> new
    return commits


def parse_json_from_commit(commit_hash, git_path):
    content = run_git(["show", "{}:{}".format(commit_hash, git_path)])
    try:
        parsed = json.loads(content)
    except Exception as ex:
        raise RuntimeError(
            "提交 {} 的 {} 不是合法 JSON: {}".format(commit_hash[:7], git_path, ex)
        )
    if not isinstance(parsed, dict):
        raise RuntimeError("提交 {} 的 JSON 根节点不是对象。".format(commit_hash[:7]))
    return parsed


def as_map(node):
    return node if isinstance(node, dict) else {}


def extract_metric_map(stress_gate, metric_name):
    gate = as_map(stress_gate)
    for key in METRIC_ALIASES.get(metric_name, [metric_name]):
        value = gate.get(key)
        if isinstance(value, dict):
            cleaned = {}
            for scenario, score in value.items():
                if isinstance(score, (int, float)):
                    cleaned[str(scenario)] = float(score)
            return cleaned
    return {}


def extract_snapshot_metrics(snapshot_json, profile):
    result = {jdk: {} for jdk in JDK_ORDER}
    ci_root = as_map(snapshot_json.get("ciStressBaselines"))
    profile_node = as_map(ci_root.get(profile))

    for jdk in JDK_ORDER:
        jdk_node = as_map(profile_node.get(jdk))
        gate = as_map(jdk_node.get("stressGate"))
        metric_maps = {}
        for metric in METRICS:
            metric_maps[metric["name"]] = extract_metric_map(gate, metric["name"])
        result[jdk] = metric_maps
    return result


def format_commit_time(raw):
    try:
        dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        return dt.strftime("%Y-%m-%d %H:%M")
    except Exception:
        return raw


def format_value(metric_name, value):
    if value is None:
        return "-"
    if metric_name == "throughputBaselines":
        return "{:,.1f}".format(value)
    if metric_name == "ratioBaselines":
        return "{:.3f}".format(value)
    if metric_name == "gcBaselines":
        return "{:.2f}".format(value)
    return str(value)


def print_table(headers, rows, right_align_start=2):
    widths = [len(str(h)) for h in headers]
    for row in rows:
        for idx, cell in enumerate(row):
            widths[idx] = max(widths[idx], len(str(cell)))

    def render_row(cells):
        rendered = []
        for idx, cell in enumerate(cells):
            text = str(cell)
            if idx >= right_align_start:
                rendered.append(text.rjust(widths[idx]))
            else:
                rendered.append(text.ljust(widths[idx]))
        return " | ".join(rendered)

    separator = "-+-".join("-" * w for w in widths)
    print(render_row(headers))
    print(separator)
    for row in rows:
        print(render_row(row))


def build_rows(metric_name, snapshots):
    rows = []
    for jdk in JDK_ORDER:
        scenarios = set()
        for snap in snapshots:
            scenarios.update(
                snap["metrics"].get(jdk, {}).get(metric_name, {}).keys()
            )
        for scenario in sorted(scenarios):
            values = []
            for snap in snapshots:
                value = snap["metrics"].get(jdk, {}).get(metric_name, {}).get(scenario)
                values.append(value)
            rows.append(
                {
                    "jdk": jdk,
                    "scenario": scenario,
                    "values": values,
                }
            )
    return rows


def pick_first_last(values):
    first = None
    last = None
    for idx, val in enumerate(values):
        if isinstance(val, (int, float)):
            first = (idx, float(val))
            break
    for idx in range(len(values) - 1, -1, -1):
        val = values[idx]
        if isinstance(val, (int, float)):
            last = (idx, float(val))
            break
    return first, last


def classify_change(first_value, last_value, higher_better, stable_threshold):
    if first_value == 0.0:
        if last_value == 0.0:
            return "stable", None
        if higher_better:
            return ("improved" if last_value > 0.0 else "regressed"), None
        return ("regressed" if last_value > 0.0 else "improved"), None

    ratio = last_value / first_value
    delta_pct = (ratio - 1.0) * 100.0

    if higher_better:
        if ratio > 1.0 + stable_threshold:
            return "improved", delta_pct
        if ratio < 1.0 - stable_threshold:
            return "regressed", delta_pct
        return "stable", delta_pct

    if ratio < 1.0 - stable_threshold:
        return "improved", delta_pct
    if ratio > 1.0 + stable_threshold:
        return "regressed", delta_pct
    return "stable", delta_pct


def build_conclusion(metric, rows, snapshots, stable_threshold):
    higher_better = metric["higher_better"]
    comparable = 0
    improved = 0
    regressed = 0
    stable = 0
    best_improved = None
    worst_regressed = None

    for row in rows:
        first, last = pick_first_last(row["values"])
        if not first or not last or first[0] == last[0]:
            continue
        comparable += 1
        state, delta_pct = classify_change(
            first[1], last[1], higher_better, stable_threshold
        )
        if state == "improved":
            improved += 1
        elif state == "regressed":
            regressed += 1
        else:
            stable += 1

        if delta_pct is None:
            continue

        item = {
            "jdk": row["jdk"],
            "scenario": row["scenario"],
            "delta_pct": delta_pct,
            "first_idx": first[0],
            "last_idx": last[0],
        }

        if state == "improved":
            if higher_better:
                if best_improved is None or item["delta_pct"] > best_improved["delta_pct"]:
                    best_improved = item
            else:
                if best_improved is None or item["delta_pct"] < best_improved["delta_pct"]:
                    best_improved = item
        elif state == "regressed":
            if higher_better:
                if worst_regressed is None or item["delta_pct"] < worst_regressed["delta_pct"]:
                    worst_regressed = item
            else:
                if worst_regressed is None or item["delta_pct"] > worst_regressed["delta_pct"]:
                    worst_regressed = item

    if comparable == 0:
        return "结论：可比项不足（没有同一行可用于时间对比的数据）。"

    if improved > regressed:
        trend = "整体偏改善"
    elif regressed > improved:
        trend = "整体偏回落"
    else:
        trend = "整体较稳定"

    state_label_regressed = "恶化" if not higher_better else "下降"
    text = (
        "结论：可比项 {} 个（按每行最早/最新可用值），改善 {}，{} {}，持平 {}，{}。".format(
            comparable, improved, state_label_regressed, regressed, stable, trend
        )
    )

    if best_improved:
        best_from = snapshots[best_improved["first_idx"]]["label"]
        best_to = snapshots[best_improved["last_idx"]]["label"]
        text += " 最大改善：{} / {} ({:+.2f}%，{} -> {})。".format(
            JDK_LABEL.get(best_improved["jdk"], best_improved["jdk"]),
            best_improved["scenario"],
            best_improved["delta_pct"],
            best_from,
            best_to,
        )
    if worst_regressed:
        worst_from = snapshots[worst_regressed["first_idx"]]["label"]
        worst_to = snapshots[worst_regressed["last_idx"]]["label"]
        text += " 最大{}：{} / {} ({:+.2f}%，{} -> {})。".format(
            state_label_regressed,
            JDK_LABEL.get(worst_regressed["jdk"], worst_regressed["jdk"]),
            worst_regressed["scenario"],
            worst_regressed["delta_pct"],
            worst_from,
            worst_to,
        )
    else:
        text += " 无{}项。".format(state_label_regressed)
    return text


def main():
    args = parse_args()
    _, git_path = resolve_git_path(args.baseline_file)

    commits = list_target_commits(
        git_path=git_path,
        limit=args.limit,
        all_commits=args.all,
        include_non_github=args.include_non_github,
    )

    snapshots = []
    for commit in commits:
        parsed = parse_json_from_commit(commit["hash"], git_path)
        metrics = extract_snapshot_metrics(parsed, args.profile)
        time_label = format_commit_time(commit["commit_time"])
        snapshots.append(
            {
                "hash": commit["hash"],
                "time": commit["commit_time"],
                "label": "{} ({})".format(time_label, commit["hash"][:7]),
                "metrics": metrics,
            }
        )

    if not snapshots:
        raise RuntimeError("没有可分析的版本快照。")

    print("基线文件: {}".format(git_path))
    print("profile : {}".format(args.profile))
    print("版本数   : {}".format(len(snapshots)))
    print("时间顺序 : {}".format(" -> ".join(s["label"] for s in snapshots)))

    for metric in METRICS:
        metric_name = metric["name"]
        rows = build_rows(metric_name, snapshots)
        table_headers = ["JDK", "场景"] + [
            "时间{} {}".format(idx + 1, snap["label"])
            for idx, snap in enumerate(snapshots)
        ]
        printable_rows = []
        for row in rows:
            printable_rows.append(
                [JDK_LABEL.get(row["jdk"], row["jdk"]), row["scenario"]]
                + [format_value(metric_name, value) for value in row["values"]]
            )

        print("\n==================== {} ====================".format(metric["title"]))
        if printable_rows:
            print_table(table_headers, printable_rows, right_align_start=2)
        else:
            print("无数据")
        print(build_conclusion(metric, rows, snapshots, args.stable_threshold))


if __name__ == "__main__":
    try:
        main()
    except Exception as ex:
        print("ERROR: {}".format(ex), file=sys.stderr)
        sys.exit(1)
