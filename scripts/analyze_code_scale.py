#!/usr/bin/env python3
"""
Analyze project code scale and optionally update README statistics section.

Usage:
  python3 scripts/analyze_code_scale.py
  python3 scripts/analyze_code_scale.py --format text
  python3 scripts/analyze_code_scale.py --write-readme
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
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
    ".gradle",
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
    ".factories",
    ".imports",
}

MAVEN_NS = "http://maven.apache.org/POM/4.0.0"
README_SECTION_TITLE = "## 二十二、代码规模统计（自动生成）"
README_START_MARKER = "<!-- CODE_SCALE_STATS:START -->"
README_END_MARKER = "<!-- CODE_SCALE_STATS:END -->"

TEST_CLASS_NAME_RE = re.compile(r"(?:Test|Tests|IT|ITCase|IntegrationTest)$")
TYPE_DECL_RE = re.compile(r"\b(class|interface|enum|record|@interface)\s+([A-Za-z_]\w*)")
TEST_ANNOTATION_RE = re.compile(
    r"(?m)^\s*@(?:org\.junit(?:\.jupiter(?:\.api)?)?\.)?"
    r"(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b"
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
        choices=("text", "markdown", "json"),
        default="markdown",
        help="Output format (default: markdown).",
    )
    parser.add_argument(
        "--top",
        type=int,
        default=8,
        help="Top N rows for module/file-type tables (default: 8).",
    )
    parser.add_argument(
        "--write-readme",
        action="store_true",
        help="Write generated markdown block back to README statistics section.",
    )
    parser.add_argument(
        "--readme",
        type=Path,
        default=None,
        help="README path used with --write-readme (default: <root>/README.md).",
    )
    parser.add_argument(
        "--quiet",
        action="store_true",
        help="Do not print formatted report to stdout.",
    )
    return parser.parse_args()


def is_excluded(rel_path: Path) -> bool:
    return any(part in EXCLUDED_DIRS for part in rel_path.parts)


def iter_files(root: Path) -> Iterable[Path]:
    for current_root, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDED_DIRS]
        current_root_path = Path(current_root)
        for filename in filenames:
            path = current_root_path / filename
            rel = path.relative_to(root)
            if is_excluded(rel):
                continue
            yield path


def read_text_file(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return path.read_text(encoding="utf-8", errors="ignore")


def is_text_source(path: Path) -> bool:
    return path.suffix.lower() in TEXT_EXTENSIONS


def is_java_file(path: Path) -> bool:
    return path.suffix.lower() == ".java"


def is_test_java(path: Path) -> bool:
    return "/src/test/java/" in path.as_posix()


def classify_java_lines(content: str) -> Tuple[int, int, int]:
    """
    Return (code_lines, comment_lines, blank_lines) for Java source.
    Rules:
    - Lines with code and trailing comments count as code lines.
    - Pure comment lines count as comment lines.
    - Whitespace-only lines count as blank lines.
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
            code_lines += 1

    return code_lines, comment_lines, blank_lines


def count_type_declarations(java_content: str) -> int:
    return len(TYPE_DECL_RE.findall(java_content))


def count_test_methods(java_content: str) -> int:
    return len(TEST_ANNOTATION_RE.findall(java_content))


def extract_package(java_content: str) -> str | None:
    match = PACKAGE_RE.search(java_content)
    return match.group(1) if match else None


def parse_modules_from_pom(pom_path: Path) -> List[str]:
    try:
        root = ET.parse(pom_path).getroot()
    except ET.ParseError:
        return []

    modules_elem = root.find(f"{{{MAVEN_NS}}}modules")
    if modules_elem is None:
        modules_elem = root.find("modules")
    if modules_elem is None:
        return []

    module_elems = modules_elem.findall(f"{{{MAVEN_NS}}}module")
    if not module_elems:
        module_elems = modules_elem.findall("module")

    modules: List[str] = []
    for elem in module_elems:
        if elem.text and elem.text.strip():
            modules.append(elem.text.strip())
    return modules


def discover_maven_modules(root: Path) -> Set[Path]:
    root = root.resolve()
    root_pom = root / "pom.xml"

    # Fallback: if root is not a Maven aggregator, count every discovered pom.
    if not root_pom.exists():
        modules = set()
        for pom in root.rglob("pom.xml"):
            rel = pom.relative_to(root)
            if is_excluded(rel):
                continue
            modules.add(pom.parent.resolve())
        return modules

    modules: Set[Path] = {root}
    queue: List[Path] = [root]
    visited: Set[Path] = set()

    while queue:
        module_dir = queue.pop(0)
        if module_dir in visited:
            continue
        visited.add(module_dir)

        pom_path = module_dir / "pom.xml"
        if not pom_path.exists():
            continue

        for module_rel in parse_modules_from_pom(pom_path):
            child = (module_dir / module_rel).resolve()
            if child in modules:
                continue
            if not child.exists():
                continue
            modules.add(child)
            queue.append(child)

    return modules


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
    root = root.resolve()
    files = list(iter_files(root))
    text_files = [p for p in files if is_text_source(p)]
    java_files = [p for p in files if is_java_file(p)]
    main_java_files = [p for p in java_files if not is_test_java(p)]
    test_java_files = [p for p in java_files if is_test_java(p)]

    module_dirs = discover_maven_modules(root)

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
    module_java_code_lines: Counter[str] = Counter()
    extension_counter: Counter[str] = Counter()

    for file in text_files:
        extension_counter[file.suffix.lower()] += 1
        content = read_text_file(file)
        lines = content.splitlines()
        totals["text_total_lines"] += len(lines)
        totals["text_non_blank_lines"] += sum(1 for line in lines if line.strip())

    for file in java_files:
        content = read_text_file(file)

        total_lines = len(content.splitlines())
        code_lines, comment_lines, blank_lines = classify_java_lines(content)
        type_count = count_type_declarations(content)
        package_name = extract_package(content)
        if package_name:
            unique_packages.add(package_name)

        module = resolve_module(file, root, module_dirs)
        module_java_code_lines[module] += code_lines

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

    result: Dict[str, object] = {
        "root": str(root),
        "timestamp": dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "pom_files": len(module_dirs),
        "child_modules": max(len(module_dirs) - 1, 0),
        "all_files": len(files),
        "text_files": len(text_files),
        "java_files_total": len(java_files),
        "java_files_main": len(main_java_files),
        "java_files_test": len(test_java_files),
        "unique_packages": len(unique_packages),
        "extension_counter": extension_counter,
        "module_java_code_lines": module_java_code_lines,
        **totals,
    }
    return result


def top_items(counter: Counter[str], limit: int) -> List[Tuple[str, int]]:
    return sorted(counter.items(), key=lambda item: (-item[1], item[0]))[:limit]


def as_text(data: Dict[str, object], top_n: int) -> str:
    top_modules = top_items(data["module_java_code_lines"], top_n)  # type: ignore[arg-type]
    ext_counter: Counter[str] = data["extension_counter"]  # type: ignore[assignment]
    top_ext = top_items(ext_counter, top_n)

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
        (
            f"- Java 文件总数: {data['java_files_total']} "
            f"(main={data['java_files_main']}, test={data['java_files_test']})"
        ),
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
        f"[Java 代码行 Top {top_n} 模块]",
    ]

    for idx, (module, code_lines) in enumerate(top_modules, start=1):
        lines.append(f"{idx}. {module}: {code_lines}")

    lines.extend(
        [
            "",
            f"[主流文件类型分布 Top {top_n}]",
        ]
    )
    for idx, (ext, count) in enumerate(top_ext, start=1):
        lines.append(f"{idx}. {ext or '[no-ext]'}: {count}")

    return "\n".join(lines)


def as_markdown(data: Dict[str, object], top_n: int) -> str:
    top_modules = top_items(data["module_java_code_lines"], top_n)  # type: ignore[arg-type]
    ext_counter: Counter[str] = data["extension_counter"]  # type: ignore[assignment]
    top_ext = top_items(ext_counter, top_n)

    md = [
        "### 代码规模快照（自动统计）",
        "",
        f"- 统计时间：`{data['timestamp']}`",
        f"- 统计脚本：`python3 scripts/analyze_code_scale.py --write-readme`",
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
        f"**Java 代码行 Top {top_n} 模块**",
        "",
        "| 排名 | 模块 | Java 代码行 |",
        "|---:|---|---:|",
    ]

    for idx, (module, code_lines) in enumerate(top_modules, start=1):
        md.append(f"| {idx} | `{module}` | {code_lines} |")

    md.extend(
        [
            "",
            f"**主流文件类型分布 Top {top_n}**",
            "",
            "| 排名 | 扩展名 | 文件数 |",
            "|---:|---|---:|",
        ]
    )
    for idx, (ext, count) in enumerate(top_ext, start=1):
        display_ext = ext if ext else "[no-ext]"
        md.append(f"| {idx} | `{display_ext}` | {count} |")

    return "\n".join(md)


def as_json(data: Dict[str, object], top_n: int) -> str:
    payload = dict(data)
    module_counter: Counter[str] = payload["module_java_code_lines"]  # type: ignore[assignment]
    ext_counter: Counter[str] = payload["extension_counter"]  # type: ignore[assignment]
    payload["module_java_code_lines"] = dict(module_counter)
    payload["extension_counter"] = dict(ext_counter)
    payload["top_modules"] = top_items(module_counter, top_n)
    payload["top_extensions"] = top_items(ext_counter, top_n)
    return json.dumps(payload, ensure_ascii=False, indent=2)


def replace_marked_block(content: str, generated_markdown: str) -> Tuple[str, bool]:
    block = (
        f"{README_START_MARKER}\n"
        f"{generated_markdown.rstrip()}\n"
        f"{README_END_MARKER}"
    )
    pattern = re.compile(
        rf"{re.escape(README_START_MARKER)}.*?{re.escape(README_END_MARKER)}",
        re.DOTALL,
    )
    new_content, count = pattern.subn(block, content, count=1)
    return new_content, count > 0


def insert_section_block(content: str, generated_markdown: str) -> str:
    heading_index = content.find(README_SECTION_TITLE)
    if heading_index < 0:
        raise RuntimeError(f"README section not found: {README_SECTION_TITLE}")

    section_body_start = heading_index + len(README_SECTION_TITLE)
    next_h2_match = re.search(r"(?m)^##\s+", content[section_body_start:])
    section_body_end = (
        section_body_start + next_h2_match.start()
        if next_h2_match is not None
        else len(content)
    )

    old_body = content[section_body_start:section_body_end]
    has_horizontal_rule = bool(re.search(r"(?m)^---\s*$", old_body))

    block = (
        f"{README_START_MARKER}\n"
        f"{generated_markdown.rstrip()}\n"
        f"{README_END_MARKER}"
    )

    new_body = f"\n\n{block}\n\n"
    if has_horizontal_rule:
        new_body += "---\n\n"

    return content[:section_body_start] + new_body + content[section_body_end:]


def update_readme(readme_path: Path, generated_markdown: str) -> bool:
    content = readme_path.read_text(encoding="utf-8")
    new_content, replaced = replace_marked_block(content, generated_markdown)
    if not replaced:
        new_content = insert_section_block(content, generated_markdown)

    if new_content == content:
        return False

    readme_path.write_text(new_content, encoding="utf-8")
    return True


def main() -> None:
    args = parse_args()
    root = args.root.resolve()
    report = analyze(root)

    markdown_output = as_markdown(report, args.top)
    if args.format == "text":
        output = as_text(report, args.top)
    elif args.format == "json":
        output = as_json(report, args.top)
    else:
        output = markdown_output

    if args.write_readme:
        readme_path = args.readme.resolve() if args.readme else (root / "README.md")
        changed = update_readme(readme_path, markdown_output)
        action = "updated" if changed else "unchanged"
        print(f"[code-scale] README {action}: {readme_path}", file=sys.stderr)

    if not args.quiet:
        print(output)


if __name__ == "__main__":
    main()
