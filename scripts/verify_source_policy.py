#!/usr/bin/env python3
"""Fail closed on local absolute paths and run scanner self-tests."""

from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path


TEXT_SUFFIXES = {
    ".bat", ".gradle", ".json", ".md", ".properties", ".py", ".sh", ".toml",
    ".txt", ".yml", ".yaml", ".java", ".xml",
}
EXCLUDED_PARTS = {".git", ".gradle", "build", "run", "__pycache__"}
LOCAL_ROOTS = tuple("/" + item for item in ("tmp", "home", "Users", "private", "var"))


def local_path_hits(text: str) -> list[str]:
    alternatives = "|".join(re.escape(root) for root in LOCAL_ROOTS)
    expression = re.compile(r"(?<![A-Za-z0-9_.-])(?:" + alternatives + r")(?:/|$)")
    return [match.group(0) for match in expression.finditer(text)]


def scan_text(path: Path, text: str) -> list[str]:
    return local_path_hits(text)


def source_files(root: Path, tracked_only: bool) -> list[Path]:
    if tracked_only:
        result = subprocess.run(
            ["git", "ls-files", "-z", "--", "."],
            cwd=root,
            check=True,
            stdout=subprocess.PIPE,
            text=False,
        ).stdout.split(b"\0")
        return [Path(item.decode()) for item in result if item]
    return [
        path for path in root.rglob("*")
        if path.is_file() and not (set(path.relative_to(root).parts) & EXCLUDED_PARTS)
    ]


def run_self_test() -> None:
    local_tmp = "/" + "tmp" + "/jdk-archive.tar.gz"
    local_home = "/" + "home" + "/developer/worktree"
    assert local_path_hits(local_tmp), "scanner failed to reject a temporary absolute path"
    assert local_path_hits(local_home), "scanner failed to reject a home absolute path"
    assert not local_path_hits("JAVA_HOME=${TEMURIN_17_HOME}"), "environment expression was rejected"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--tracked-only", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    root = args.repo_root.resolve()
    if args.self_test:
        run_self_test()

    failures: list[str] = []
    for path in source_files(root, args.tracked_only):
        candidate = path if path.is_absolute() else root / path
        if candidate.suffix not in TEXT_SUFFIXES:
            continue
        try:
            text = candidate.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        hits = scan_text(candidate, text)
        failures.extend(f"{candidate}: local absolute path fragment {hit!r}" for hit in hits)
    if failures:
        print("source policy rejected local absolute paths:")
        print("\n".join(failures))
        return 1
    print("source policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
