#!/usr/bin/env python3
"""Run a fresh-process ForgeGradle dedicated-server smoke with bound evidence.

The smoke deliberately proves a userdev server run only. It does not claim a
packaged-artifact installation test. A pass requires one process group started
through this repository's fixed Gradle wrapper and one newly-created
``logs/latest.log`` in a unique run directory carrying the invocation ID,
``reality_foundation`` server-load marker, and Forge readiness marker.
"""

from __future__ import annotations

import argparse
import errno
import os
from pathlib import Path
import selectors
import signal
import subprocess
import tempfile
import time
import uuid


EVIDENCE_PROPERTY = "foundation.evidence.run_id="
LOAD_MARKER = "mod_id=reality_foundation server_started"
CLIENT_ONLY_MARKERS = ("FoundationClient", "DiagnosticsScreen")
READY_MARKERS = ("Done (", "For help, type")
# Some CI filesystems persist timestamps below nanosecond precision.  This is
# deliberately tiny: the unique run directory and UUID evidence marker still
# bind the log to this child invocation, while a log from a prior run remains
# many orders of magnitude older than this storage-rounding allowance.
FRESHNESS_PRECISION_NANOS = 1_000_000


def expected_evidence_marker(run_id: str) -> str:
    return f"{EVIDENCE_PROPERTY}{run_id} {LOAD_MARKER}"


def ready_marker_present(text: str) -> bool:
    return any(marker in text for marker in READY_MARKERS)


def dedicated_log(run_dir: Path) -> Path:
    return run_dir / "logs" / "latest.log"


def read_bound_log(run_dir: Path, run_id: str, started_at_ns: int) -> str:
    """Read only a freshly-created dedicated latest.log, never a log glob."""
    log = dedicated_log(run_dir)
    if not log.is_file():
        raise ValueError(f"dedicated smoke log was not created: {log}")
    stat = log.stat()
    if (stat.st_mtime_ns + FRESHNESS_PRECISION_NANOS < started_at_ns
            or stat.st_ctime_ns + FRESHNESS_PRECISION_NANOS < started_at_ns):
        raise ValueError("dedicated smoke log predates this process invocation")
    text = log.read_text(encoding="utf-8", errors="replace")
    if expected_evidence_marker(run_id) not in text:
        raise ValueError("dedicated smoke log lacks this invocation's evidence marker")
    if not ready_marker_present(text):
        raise ValueError("dedicated smoke log lacks the server readiness marker")
    if any(marker in text for marker in CLIENT_ONLY_MARKERS):
        raise ValueError("client-only foundation class appeared in dedicated-server log")
    return text


def fixed_gradle_wrapper(root: Path, requested: Path) -> Path:
    """Accept only the repository's fixed wrapper, never arbitrary commands."""
    wrapper = requested if requested.is_absolute() else root / requested
    wrapper = wrapper.resolve()
    expected = (root / "gradlew").resolve()
    if wrapper != expected or not wrapper.is_file():
        raise ValueError("smoke requires this repository's fixed gradlew wrapper")
    wrapper_text = wrapper.read_text(encoding="utf-8", errors="replace")
    if "EXPECTED_VERSION=8.8" not in wrapper_text or "EXPECTED_SHA256=" not in wrapper_text:
        raise ValueError("smoke wrapper is not the fixed Gradle 8.8 launcher")
    return wrapper


def exact_temurin_java_home(requested: Path | None) -> Path:
    """Resolve only the fixed JDK release used by the Foundation train."""
    candidate = requested
    if candidate is None:
        configured = os.environ.get("JAVA_HOME")
        if not configured:
            raise ValueError("dedicated smoke requires an explicit Temurin 17.0.20+8 JDK")
        candidate = Path(configured)
    candidate = candidate.resolve()
    java = candidate / "bin" / "java"
    if not java.is_file():
        raise ValueError("dedicated smoke requires an executable Temurin 17.0.20+8 JDK")
    probe = subprocess.run(
        [str(java), "-XshowSettings:properties", "-version"],
        check=False, capture_output=True, text=True)
    details = probe.stdout + probe.stderr
    normalized = " ".join(details.split())
    if (probe.returncode != 0
            or "java.runtime.version = 17.0.20+8" not in normalized
            or "java.vendor = Eclipse Adoptium" not in normalized):
        raise ValueError("dedicated smoke requires Temurin 17.0.20+8")
    return candidate


def process_group_exists(process_group_id: int) -> bool:
    try:
        os.killpg(process_group_id, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def stop_process_group(process: subprocess.Popen[str]) -> bool:
    """Stop only this invocation's process group and report forced fallback."""
    if process.poll() is not None and not process_group_exists(process.pid):
        return False
    forced = False
    try:
        os.killpg(process.pid, signal.SIGTERM)
        forced = True
    except ProcessLookupError:
        return False
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=10)
    deadline = time.monotonic() + 5.0
    while process_group_exists(process.pid) and time.monotonic() < deadline:
        time.sleep(0.05)
    if process_group_exists(process.pid):
        raise ValueError("dedicated smoke left its own process group running")
    return forced


def build_command(
        wrapper: Path, run_dir: Path, run_id: str, core_dir: Path | None,
        java_home: Path) -> list[str]:
    command = [
        "bash", str(wrapper), "--dependency-verification=strict", "--offline",
        "--no-daemon", "--console=plain", f"-PfoundationRunDir={run_dir}",
        f"-PfoundationEvidenceRunId={run_id}",
    ]
    command.extend((f"-Dorg.gradle.java.home={java_home}",
                    f"-Dorg.gradle.java.installations.paths={java_home}"))
    if core_dir is not None:
        command.append(f"-PrealityCoreDir={core_dir.resolve()}")
    command.append(":forge-1.20.1:runServer")
    return command


def create_run_dir(root: Path, run_base: Path | None) -> Path:
    base = (run_base if run_base is not None else root / "forge-1.20.1" / "build" / "smoke-runs")
    base.mkdir(parents=True, exist_ok=True)
    return Path(tempfile.mkdtemp(prefix="server-smoke-", dir=base))


def tail(text: str, lines: int = 30) -> str:
    return "".join(text.splitlines(keepends=True)[-lines:])


def run_smoke(args: argparse.Namespace) -> int:
    root = args.repo_root.resolve()
    wrapper = fixed_gradle_wrapper(root, args.gradle_command)
    java_home = exact_temurin_java_home(args.java_home)
    run_id = f"smoke-{uuid.uuid4()}"
    run_dir = create_run_dir(root, args.run_base)
    (run_dir / "eula.txt").write_text(
        "# Accepted only for this isolated ignored verification run.\neula=true\n", encoding="utf-8")
    (run_dir / "server.properties").write_text(
        "# Generated only for the isolated userdev smoke.\n"
        "server-port=25566\n"
        "online-mode=false\n", encoding="utf-8")
    command = build_command(wrapper, run_dir, run_id, args.core_dir, java_home)
    child_environment = os.environ.copy()
    child_environment["JAVA_HOME"] = str(java_home)
    started_at_ns = time.time_ns()
    process = subprocess.Popen(
        command, cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True, bufsize=1, env=child_environment,
        start_new_session=True)
    if process.pid <= 0 or not process_group_exists(process.pid):
        raise ValueError("Forge Gradle child process group was not started")

    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    output: list[str] = []
    ready = False
    stop_sent = False
    forced_stop = False
    normal_stop_deadline: float | None = None
    deadline = time.monotonic() + args.timeout_seconds
    try:
        while time.monotonic() < deadline and process.poll() is None:
            for _, _ in selector.select(timeout=0.5):
                line = process.stdout.readline()
                if line:
                    output.append(line)
            if not ready:
                try:
                    read_bound_log(run_dir, run_id, started_at_ns)
                    ready = True
                    normal_stop_deadline = time.monotonic() + 15.0
                except ValueError:
                    pass
            if ready and not stop_sent:
                stop_sent = True
                if process.stdin is not None:
                    process.stdin.write("stop\n")
                    process.stdin.flush()
            if ready and normal_stop_deadline is not None and time.monotonic() >= normal_stop_deadline:
                forced_stop = stop_process_group(process)
                break
        if process.poll() is None:
            forced_stop = stop_process_group(process) or forced_stop
    finally:
        selector.close()
        if process.poll() is None or process_group_exists(process.pid):
            forced_stop = stop_process_group(process) or forced_stop

    log_text = ""
    evidence_error: ValueError | None = None
    try:
        log_text = read_bound_log(run_dir, run_id, started_at_ns)
    except ValueError as failure:
        evidence_error = failure
    if evidence_error is not None:
        raise ValueError(
            f"userdev dedicated-server smoke evidence failed: {evidence_error}\n{tail(''.join(output) + log_text)}")
    if not ready:
        raise ValueError("userdev dedicated-server smoke reached no bound readiness evidence")
    if not forced_stop and process.returncode != 0:
        raise ValueError(f"Forge Gradle server process exited {process.returncode}\n{tail(''.join(output) + log_text)}")
    if process_group_exists(process.pid):
        raise ValueError("dedicated smoke left its own process group running")

    status = "FORCED_PROCESS_GROUP_STOP" if forced_stop else "NORMAL_STOP"
    print(f"ForgeGradle userdev dedicated-server smoke passed status={status}")
    print(f"run_id={run_id} run_dir={run_dir} child_pid={process.pid} evidence_log={dedicated_log(run_dir)}")
    return 0


def expect_failure(callback, description: str) -> None:
    try:
        callback()
    except ValueError:
        return
    raise AssertionError(f"negative smoke fixture was accepted: {description}")


def self_test() -> None:
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        wrapper = root / "gradlew"
        wrapper.write_text("EXPECTED_VERSION=8.8\nEXPECTED_SHA256=fixture\n", encoding="utf-8")
        run_dir = root / "run"
        log = dedicated_log(run_dir)
        log.parent.mkdir(parents=True)
        run_id = "smoke-12345678-1234-1234-1234-123456789abc"
        started_at_ns = time.time_ns()
        log.write_text(
            f"{expected_evidence_marker(run_id)}\nDone (fixture)\n", encoding="utf-8")
        assert read_bound_log(run_dir, run_id, started_at_ns)

        stale = root / "stale"
        stale_log = dedicated_log(stale)
        stale_log.parent.mkdir(parents=True)
        stale_log.write_text(f"{expected_evidence_marker(run_id)}\nDone (old)\n", encoding="utf-8")
        old_ns = started_at_ns - 10_000_000_000
        os.utime(stale_log, ns=(old_ns, old_ns))
        expect_failure(lambda: read_bound_log(stale, run_id, started_at_ns), "old Done")

        touched = root / "touched"
        touched_log = dedicated_log(touched)
        touched_log.parent.mkdir(parents=True)
        touched_log.write_text("Done (touched stale)\n", encoding="utf-8")
        expect_failure(lambda: read_bound_log(touched, run_id, started_at_ns), "touched stale log")

        no_done = root / "no-done"
        no_done_log = dedicated_log(no_done)
        no_done_log.parent.mkdir(parents=True)
        no_done_log.write_text(expected_evidence_marker(run_id), encoding="utf-8")
        expect_failure(lambda: read_bound_log(no_done, run_id, started_at_ns), "missing Done")

        client = root / "client"
        client_log = dedicated_log(client)
        client_log.parent.mkdir(parents=True)
        client_log.write_text(
            f"{expected_evidence_marker(run_id)}\nDone (fixture)\nFoundationClient\n",
            encoding="utf-8")
        expect_failure(lambda: read_bound_log(client, run_id, started_at_ns), "client-only load")

        for fake_name in ("sleep", "exit0", "exit1"):
            fake = root / fake_name
            fake.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            expect_failure(lambda fake=fake: fixed_gradle_wrapper(root, fake), fake_name)

        process = subprocess.Popen(["bash", "-c", "sleep 30"], start_new_session=True)
        assert process_group_exists(process.pid)
        assert stop_process_group(process)
        assert not process_group_exists(process.pid)
    print("userdev smoke negative harness self-test passed (stale/touched/fake/client/process cases)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path("."))
    parser.add_argument("--gradle-command", type=Path, default=Path("./gradlew"))
    parser.add_argument("--core-dir", type=Path)
    parser.add_argument("--java-home", type=Path,
                        help="exact Eclipse Temurin 17.0.20+8 home (defaults to JAVA_HOME)")
    parser.add_argument("--run-base", type=Path)
    parser.add_argument("--timeout-seconds", type=float, default=180.0)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.timeout_seconds <= 0:
        raise ValueError("timeout-seconds must be positive")
    return run_smoke(args)


if __name__ == "__main__":
    raise SystemExit(main())
