#!/usr/bin/env python3
"""Validate exactly one fresh Reality Foundation GameTest success.

The verifier intentionally accepts a single explicit ``latest.log`` or a
unique run directory. It never concatenates old logs, debug logs, XML reports,
or another namespace into a synthetic pass.  The run-directory mode rejects a
second proof log (including a copied ``debug.log``); Forge's normal mirrored
debug log is instead handled by passing the one explicit, isolated
``logs/latest.log`` path used by CI.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import tempfile
import time


NAMESPACE = "reality_foundation"
TEST_NAME = "FoundationGameTests.contextRequestMenuPermissionAndRegeneration"
NAMESPACE_MARKER = f"FoundationGameTests namespace={NAMESPACE} exact_test={TEST_NAME}"
PASS_MARKER = f"{TEST_NAME} PASS"
RUNNING_PATTERN = re.compile(r"\bRunning 1 tests\b")
COMPLETE_PATTERN = re.compile(r"\bAll 1 required tests passed\b")
ZERO_PATTERN = re.compile(r"\b(?:Running|All) 0 (?:required )?tests\b", re.IGNORECASE)


def evidence_marker(run_id: str) -> str:
    return f"foundation.evidence.run_id={run_id} mod_id=reality_foundation server_started"


def count_lines(lines: list[str], marker_or_pattern: str | re.Pattern[str]) -> int:
    if isinstance(marker_or_pattern, str):
        return sum(marker_or_pattern in line for line in lines)
    return sum(marker_or_pattern.search(line) is not None for line in lines)


def assert_single_log(log_file: Path, run_id: str, started_at_ns: int) -> None:
    if log_file.name != "latest.log" or not log_file.is_file():
        raise ValueError("GameTest input must be one explicit latest.log")
    stat = log_file.stat()
    if stat.st_mtime_ns < started_at_ns or stat.st_ctime_ns < started_at_ns:
        raise ValueError("GameTest latest.log predates this invocation")
    text = log_file.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    if evidence_marker(run_id) not in text:
        raise ValueError("GameTest latest.log lacks this invocation's evidence marker")
    if "No tests to run" in text or ZERO_PATTERN.search(text):
        raise ValueError("zero-test GameTest run cannot pass")
    if re.search(r"FoundationGameTests namespace=(?!reality_foundation\b)", text):
        raise ValueError("GameTest ran a different namespace")
    required = {
        "namespace": count_lines(lines, NAMESPACE_MARKER),
        "running": count_lines(lines, RUNNING_PATTERN),
        "pass": count_lines(lines, PASS_MARKER),
        "complete": count_lines(lines, COMPLETE_PATTERN),
    }
    bad_counts = {name: count for name, count in required.items() if count != 1}
    if bad_counts:
        raise ValueError(f"GameTest proof must occur exactly once in one log: {bad_counts}")
    test_pass_lines = [line for line in lines if "FoundationGameTests." in line and " PASS" in line]
    if test_pass_lines != [next(line for line in lines if PASS_MARKER in line)]:
        raise ValueError("GameTest log contains a non-exact Foundation test PASS")
    if re.search(r"required tests failed|foundationgametests\.[^ ]+ failed!", text, re.IGNORECASE):
        raise ValueError("GameTest run recorded a required failure")


def log_from_unique_run_dir(run_dir: Path, run_id: str, started_at_ns: int) -> Path:
    log = run_dir / "logs" / "latest.log"
    if not log.is_file():
        raise ValueError("GameTest run directory has no logs/latest.log")
    proof_logs: list[Path] = []
    for candidate in run_dir.rglob("*.log"):
        if candidate.stat().st_mtime_ns < started_at_ns:
            continue
        text = candidate.read_text(encoding="utf-8", errors="replace")
        if NAMESPACE_MARKER in text or PASS_MARKER in text:
            proof_logs.append(candidate)
    if proof_logs != [log]:
        names = ", ".join(str(path.relative_to(run_dir)) for path in proof_logs)
        raise ValueError(f"GameTest run directory has duplicate or missing proof logs: {names or 'none'}")
    return log


def validate(log_file: Path, run_id: str, started_at_ns: int) -> None:
    assert_single_log(log_file, run_id, started_at_ns)


def expect_failure(callback, description: str) -> None:
    try:
        callback()
    except ValueError:
        return
    raise AssertionError(f"negative GameTest fixture was accepted: {description}")


def fixture_text(run_id: str) -> str:
    return "\n".join((
        evidence_marker(run_id),
        NAMESPACE_MARKER,
        "Running 1 tests",
        PASS_MARKER,
        "All 1 required tests passed",
        "",
    ))


def write_fixture(path: Path, text: str) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    started_at_ns = time.time_ns()
    path.write_text(text, encoding="utf-8")
    return started_at_ns


def self_test() -> None:
    run_id = "gametest-12345678-1234-1234-1234-123456789abc"
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        latest = root / "logs" / "latest.log"
        started_at_ns = write_fixture(latest, fixture_text(run_id))
        validate(latest, run_id, started_at_ns)

        stale = root / "stale" / "logs" / "latest.log"
        stale_start = write_fixture(stale, fixture_text(run_id))
        old_ns = stale_start - 10_000_000_000
        os.utime(stale, ns=(old_ns, old_ns))
        expect_failure(lambda: validate(stale, run_id, stale_start), "stale PASS")

        zero = root / "zero" / "logs" / "latest.log"
        zero_start = write_fixture(zero, fixture_text(run_id).replace(
            "Running 1 tests", "No tests to run\nRunning 0 tests"))
        expect_failure(lambda: validate(zero, run_id, zero_start), "zero tests")

        double = root / "double" / "logs" / "latest.log"
        double_start = write_fixture(double, fixture_text(run_id) + PASS_MARKER + "\n")
        expect_failure(lambda: validate(double, run_id, double_start), "two exact PASS markers")

        other_namespace = root / "other" / "logs" / "latest.log"
        other_start = write_fixture(other_namespace, fixture_text(run_id).replace(
            NAMESPACE_MARKER, f"FoundationGameTests namespace=other_namespace exact_test={TEST_NAME}"))
        expect_failure(lambda: validate(other_namespace, run_id, other_start), "other namespace")

        duplicate_dir = root / "duplicate"
        duplicate_latest = duplicate_dir / "logs" / "latest.log"
        duplicate_start = write_fixture(duplicate_latest, fixture_text(run_id))
        debug = duplicate_dir / "logs" / "debug.log"
        debug.write_text(fixture_text(run_id), encoding="utf-8")
        expect_failure(lambda: log_from_unique_run_dir(duplicate_dir, run_id, duplicate_start),
                       "latest/debug duplicate")
    print("GameTest log negative harness self-test passed (stale/zero/double/namespace/duplicate cases)")


def main() -> int:
    parser = argparse.ArgumentParser()
    input_group = parser.add_mutually_exclusive_group()
    input_group.add_argument("--run-dir", type=Path)
    input_group.add_argument("--log-file", type=Path)
    parser.add_argument("--run-id")
    parser.add_argument("--started-at-ns", type=int)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.run_id is None or args.started_at_ns is None:
        raise ValueError("run-id and started-at-ns are required for fresh GameTest evidence")
    if args.started_at_ns <= 0:
        raise ValueError("started-at-ns must be positive")
    if args.log_file is not None:
        log_file = args.log_file
    elif args.run_dir is not None:
        log_file = log_from_unique_run_dir(args.run_dir, args.run_id, args.started_at_ns)
    else:
        raise ValueError("one of --log-file or --run-dir is required")
    validate(log_file, args.run_id, args.started_at_ns)
    print(f"GameTest log passed exactly once: {TEST_NAME} ({log_file})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
