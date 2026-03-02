#!/usr/bin/env python3
"""
Analyze project code scale with a Java-focused metric set.

Usage:
  python3 scripts/analyze_code_scale.py
  python3 scripts/analyze_code_scale.py --root /path/to/project
  python3 scripts/analyze_code_scale.py --format markdown
"""

from __future__ import annotations

import argparse
import datetime as dt
import re
from collections import Counter
from pathlib import Path
from typing import Dict, Iterable, List, Set, Tuple


EXCLUDED_DIRS = {
    ".git",
    ".idea",
    ".mvn",
    ".vscode",
    "target",
    "build",
    "out",
    "__pycache__",
    "node_modules",
}

TEXT_EXTENSIONS = {
    ".java",
    ".kt",
    ".kts",
    ".groovy",
    ".py",
    ".xml",
    ".yaml",
    ".yml",
    ".properties",
    ".json",
    ".toml",
    ".sql",
    ".sh",
    ".md",
}

TEST_CLASS_NAME_RE = re.compile(r"(?:Test|Tests|IT|ITCase|IntegrationTest)$")
TYPE_DECL_RE = re.compile(r"\b(class|interface|enum|record|@interface)\s+([A-Za-z_]\w*)")
TEST_ANNOTATION_RE = re.compile(
    r"(?m)^\s*@(?:org\.junit\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b"
)
PACKAGE_RE = re.compile(r"(?m)^\s*package\s+([A-Za-z_][\w.]*)\s*;")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Analyze project code scale.")
    parser.add_argument(
        "--root",
        type=Path,
        default=Path("."),
        help="Project root directory (default: current directory).",
    )
    parser.add_argument(
        "--format",
        choices=("text", "markdown"),
        default="text",
        help="Output format (default: text).",
    )
    return parser.parse_args()


def iter_files(root: Path) -> Iterable[Path]:
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        rel_parts = path.relative_to(root).parts
        if any(part in EXCLUDED_DIRS for part in rel_parts):
            continue
        yield path


def is_text_source(path: Path) -> bool:
    return path.suffix.lower() in TEXT_EXTENSIONS


def is_java_file(path: Path) -> bool:
    return path.suffix.lower() == ".java"


def is_test_java(path: Path) -> bool:
    normalized = path.as_posix()
    return "/src/test/java/" in normalized


def classify_java_lines(content: str) -> Tuple[int, int, int]:
    """
    Return (code_lines, comment_lines, blank_lines) for Java source.
    Rules:
    - Lines with code and trailing comments count as code lines.
    - Pure comment lines (single-line or block) count as comment lines.
    - Empty/whitespace-only lines count as blank lines.
    """
    code_lines = 0
    comment_lines = 0
    blank_lines = 0

    in_block_comment = False

    for raw_line in content.splitlines():
        line = raw_line.rstrip("\n")
        i = 0
        has_code = False
        has_comment = False
        length = len(line)

        while i < length:
            ch = line[i]
            nxt = line[i + 1] if i + 1 < length else ""

            if in_block_comment:
                has_comment = True
                if ch == "*" and nxt == "/":
                    in_block_comment = False
                    i += 2
                else:
                    i += 1
                continue

            if ch in " \t\r":
                i += 1
                continue

            if ch == "/" and nxt == "/":
                has_comment = True
                break

            if ch == "/" and nxt == "*":
                has_comment = True
                in_block_comment = True
                i += 2
                continue

            if ch == '"':
                has_code = True
                i += 1
                while i < length:
                    if line[i] == "\\" and i + 1 < length:
                        i += 2
                    elif line[i] == '"':
                        i += 1
                        break
                    else:
                        i += 1
                continue

            if ch == "'":
                has_code = True
                i += 1
                while i < length:
                    if line[i] == "\\" and i + 1 < length:
                        i += 2
                    elif line[i] == "'":
                        i += 1
                        break
                    else:
                        i += 1
                continue

            has_code = True
            i += 1

        if has_code:
            code_lines += 1
        elif has_comment:
            comment_lines += 1
        elif line.strip() == "":
            blank_lines += 1
        else:
            # Fallback: treat ambiguous non-empty lines as code.
            code_lines += 1

    return code_lines, comment_lines, blank_lines


def count_type_declarations(java_content: str) -> int:
    return len(TYPE_DECL_RE.findall(java_content))


def count_test_methods(java_content: str) -> int:
    return len(TEST_ANNOTATION_RE.findall(java_content))


def extract_package(java_content: str) -> str | None:
    match = PACKAGE_RE.search(java_content)
    return match.group(1) if match else None


def find_module_dirs(root: Path) -> Set[Path]:
    module_dirs: Set[Path] = set()
    for pom in root.rglob("pom.xml"):
        if any(part in EXCLUDED_DIRS for part in pom.relative_to(root).parts):
            continue
        module_dirs.add(pom.parent.resolve())
    return module_dirs


def resolve_module(path: Path, root: Path, module_dirs: Set[Path]) -> str:
    current = path.parent.resolve()
    root_resolved = root.resolve()

    while True:
        if current in module_dirs:
            if current == root_resolved:
                return "."
            return str(current.relative_to(root_resolved)).replace("\\", "/")
        if current == root_resolved:
            return "."
        parent = current.parent
        if parent == current:
            return "."
        current = parent


def analyze(root: Path) -> Dict[str, object]:
    files = list(iter_files(root))
    text_files = [p for p in files if is_text_source(p)]
    java_files = [p for p in files if is_java_file(p)]
    main_java_files = [p for p in java_files if not is_test_java(p)]
    test_java_files = [p for p in java_files if is_test_java(p)]

    module_dirs = find_module_dirs(root)

    totals = {
        "java_total_lines": 0,
        "java_code_lines": 0,
        "java_comment_lines": 0,
        "java_blank_lines": 0,
        "java_main_total_lines": 0,
        "java_main_code_lines": 0,
        "java_test_total_lines": 0,
        "java_test_code_lines": 0,
        "type_declarations_total": 0,
        "type_declarations_main": 0,
        "type_declarations_test": 0,
        "test_classes": 0,
        "test_methods": 0,
        "text_total_lines": 0,
        "text_non_blank_lines": 0,
    }

    unique_packages: Set[str] = set()
    module_java_code_lines: Counter = Counter()
    module_java_files: Counter = Counter()
    extension_counter: Counter = Counter()

    for file in text_files:
        extension_counter[file.suffix.lower()] += 1
        try:
            content = file.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            content = file.read_text(encoding="utf-8", errors="ignore")
        lines = content.splitlines()
        totals["text_total_lines"] += len(lines)
        totals["text_non_blank_lines"] += sum(1 for line in lines if line.strip())

    for file in java_files:
        try:
            content = file.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            content = file.read_text(encoding="utf-8", errors="ignore")

        total_lines = len(content.splitlines())
        code_lines, comment_lines, blank_lines = classify_java_lines(content)
        type_count = count_type_declarations(content)
        package_name = extract_package(content)
        if package_name:
            unique_packages.add(package_name)

        module = resolve_module(file, root, module_dirs)
        module_java_code_lines[module] += code_lines
        module_java_files[module] += 1

        totals["java_total_lines"] += total_lines
        totals["java_code_lines"] += code_lines
        totals["java_comment_lines"] += comment_lines
        totals["java_blank_lines"] += blank_lines
        totals["type_declarations_total"] += type_count

        if is_test_java(file):
            totals["java_test_total_lines"] += total_lines
            totals["java_test_code_lines"] += code_lines
            totals["type_declarations_test"] += type_count
            test_methods = count_test_methods(content)
            totals["test_methods"] += test_methods

            class_names = [name for _, name in TYPE_DECL_RE.findall(content)]
            is_named_test = any(TEST_CLASS_NAME_RE.search(name) for name in class_names)
            if test_methods > 0 or is_named_test:
                totals["test_classes"] += 1
        else:
            totals["java_main_total_lines"] += total_lines
            totals["java_main_code_lines"] += code_lines
            totals["type_declarations_main"] += type_count

    pom_files = [
        p for p in root.rglob("pom.xml") if not any(part in EXCLUDED_DIRS for part in p.relative_to(root).parts)
    ]

    result: Dict[str, object] = {
        "root": str(root.resolve()),
        "timestamp": dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "pom_files": len(pom_files),
        "child_modules": max(len(pom_files) - 1, 0),
        "all_files": len(files),
        "text_files": len(text_files),
        "java_files_total": len(java_files),
        "java_files_main": len(main_java_files),
        "java_files_test": len(test_java_files),
        "unique_packages": len(unique_packages),
        "extension_counter": extension_counter,
        "module_java_code_lines": module_java_code_lines,
        "module_java_files": module_java_files,
        **totals,
    }
    return result


def format_top_modules(module_java_code_lines: Counter, limit: int = 8) -> List[Tuple[str, int]]:
    items = sorted(module_java_code_lines.items(), key=lambda item: (-item[1], item[0]))
    return items[:limit]


def as_text(data: Dict[str, object]) -> str:
    top_modules = format_top_modules(data["module_java_code_lines"])  # type: ignore[arg-type]
    ext_counter: Counter = data["extension_counter"]  # type: ignore[assignment]

    lines = [
        "代码规模分析报告",
        f"扫描时间: {data['timestamp']}",
        f"项目目录: {data['root']}",
        "",
        "[总体]",
        f"- Maven 模块数（含父工程）: {data['pom_files']}",
        f"- Maven 子模块数: {data['child_modules']}",
        f"- 文件总数（排除构建产物目录）: {data['all_files']}",
        f"- 文本源码/配置文件数: {data['text_files']}",
        "",
        "[Java 规模]",
        f"- Java 文件总数: {data['java_files_total']} (main={data['java_files_main']}, test={data['java_files_test']})",
        f"- 类型声明总数(class/interface/enum/record/@interface): {data['type_declarations_total']}",
        f"- 生产类型声明数: {data['type_declarations_main']}",
        f"- 测试源码类型声明数: {data['type_declarations_test']}",
        f"- 测试类数（含 @Test 或命名约定）: {data['test_classes']}",
        f"- 测试方法数（@Test 系列注解）: {data['test_methods']}",
        f"- 包数量: {data['unique_packages']}",
        "",
        "[Java 行数]",
        f"- 总行数: {data['java_total_lines']}",
        f"- 代码行: {data['java_code_lines']}",
        f"- 注释行: {data['java_comment_lines']}",
        f"- 空行: {data['java_blank_lines']}",
        f"- main 代码行: {data['java_main_code_lines']} (总行 {data['java_main_total_lines']})",
        f"- test 代码行: {data['java_test_code_lines']} (总行 {data['java_test_total_lines']})",
        "",
        "[文本文件总行数]",
        f"- 总行数: {data['text_total_lines']}",
        f"- 非空行: {data['text_non_blank_lines']}",
        "",
        "[Java 代码行 Top 模块]",
    ]

    for idx, (module, code_lines) in enumerate(top_modules, start=1):
        lines.append(f"{idx}. {module}: {code_lines}")

    lines.extend(
        [
            "",
            "[主流文件类型分布 Top 8]",
        ]
    )
    top_ext = sorted(ext_counter.items(), key=lambda item: (-item[1], item[0]))[:8]
    for idx, (ext, count) in enumerate(top_ext, start=1):
        lines.append(f"{idx}. {ext or '[no-ext]'}: {count}")

    return "\n".join(lines)


def as_markdown(data: Dict[str, object]) -> str:
    top_modules = format_top_modules(data["module_java_code_lines"])  # type: ignore[arg-type]
    ext_counter: Counter = data["extension_counter"]  # type: ignore[assignment]
    top_ext = sorted(ext_counter.items(), key=lambda item: (-item[1], item[0]))[:8]

    md = [
        "### 代码规模快照（自动统计）",
        "",
        f"- 统计时间：`{data['timestamp']}`",
        f"- 统计脚本：`python3 scripts/analyze_code_scale.py`",
        "",
        "| 维度 | 数值 |",
        "|---|---:|",
        f"| Maven 模块数（含父工程） | {data['pom_files']} |",
        f"| Maven 子模块数 | {data['child_modules']} |",
        f"| 文件总数（排除构建产物目录） | {data['all_files']} |",
        f"| 文本源码/配置文件数 | {data['text_files']} |",
        f"| Java 文件总数 | {data['java_files_total']} |",
        f"| 生产 Java 文件数 | {data['java_files_main']} |",
        f"| 测试 Java 文件数 | {data['java_files_test']} |",
        f"| 类型声明总数（class/interface/enum/record/@interface） | {data['type_declarations_total']} |",
        f"| 生产类型声明数 | {data['type_declarations_main']} |",
        f"| 测试源码类型声明数 | {data['type_declarations_test']} |",
        f"| 测试类数（含 @Test 或命名约定） | {data['test_classes']} |",
        f"| 测试方法数（@Test 系列注解） | {data['test_methods']} |",
        f"| 包数量 | {data['unique_packages']} |",
        f"| Java 总行数 | {data['java_total_lines']} |",
        f"| Java 代码行 | {data['java_code_lines']} |",
        f"| Java 注释行 | {data['java_comment_lines']} |",
        f"| Java 空行 | {data['java_blank_lines']} |",
        f"| main Java 代码行 | {data['java_main_code_lines']} |",
        f"| test Java 代码行 | {data['java_test_code_lines']} |",
        f"| 文本文件总行数 | {data['text_total_lines']} |",
        f"| 文本文件非空行 | {data['text_non_blank_lines']} |",
        "",
        "**Java 代码行 Top 模块**",
        "",
        "| 排名 | 模块 | Java 代码行 |",
        "|---:|---|---:|",
    ]

    for idx, (module, code_lines) in enumerate(top_modules, start=1):
        md.append(f"| {idx} | `{module}` | {code_lines} |")

    md.extend(
        [
            "",
            "**主流文件类型分布 Top 8**",
            "",
            "| 排名 | 扩展名 | 文件数 |",
            "|---:|---|---:|",
        ]
    )
    for idx, (ext, count) in enumerate(top_ext, start=1):
        display_ext = ext if ext else "[no-ext]"
        md.append(f"| {idx} | `{display_ext}` | {count} |")

    return "\n".join(md)


def main() -> None:
    args = parse_args()
    root = args.root.resolve()
    result = analyze(root)
    output = as_markdown(result) if args.format == "markdown" else as_text(result)
    print(output)


if __name__ == "__main__":
    main()
