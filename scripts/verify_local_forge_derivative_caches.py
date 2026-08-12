#!/usr/bin/env python3
"""Materialize the one local Forge derivative in two empty Gradle homes.

This is deliberately an online-only supply-chain acquisition check.  It does
not broaden Gradle's trust boundary: each build remains strict, and the two
locally generated mapped Forge JARs are passed through the committed
canonical-content verifier before their digests are compared.
"""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

import verify_local_forge_derivative as derivative


DERIVATIVE_RELATIVE_PATH = Path(
    "caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/"
    "1.20.1-47.4.10_mapped_official_1.20.1/"
    "forge-1.20.1-47.4.10_mapped_official_1.20.1.jar")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def materialize(
        root: Path, wrapper: Path, core_dir: Path, java_home: Path,
        gradle_home: Path, manifest: Path) -> tuple[str, str]:
    if any(gradle_home.iterdir()):
        raise ValueError("fresh local-derivative Gradle home is not empty")
    environment = os.environ.copy()
    environment["GRADLE_USER_HOME"] = str(gradle_home)
    environment["JAVA_HOME"] = str(java_home)
    command = [
        "bash", str(wrapper), "--dependency-verification=strict", "--no-daemon",
        "--max-workers=1", "--console=plain",
        f"-Dorg.gradle.java.home={java_home}",
        f"-Dorg.gradle.java.installations.paths={java_home}",
        f"-PrealityCoreDir={core_dir}", ":forge-1.20.1:compileJava",
    ]
    completed = subprocess.run(
        command, cwd=root, env=environment, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    if completed.returncode != 0:
        tail = "\n".join(completed.stdout.splitlines()[-80:])
        raise ValueError(f"strict local-derivative materialization failed:\n{tail}")
    jar = gradle_home / DERIVATIVE_RELATIVE_PATH
    if not jar.is_file():
        raise ValueError("mapped Forge derivative was not materialized at the exact local path")
    return sha256(jar), derivative.verify(
        jar, derivative.EXPECTED_COMPONENT, manifest)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path("."))
    parser.add_argument("--core-dir", type=Path)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--cache-base", type=Path)
    args = parser.parse_args()

    root = args.repo_root.resolve()
    wrapper = (root / "gradlew").resolve()
    core_dir = (args.core_dir if args.core_dir is not None else root.parent / "reality-core").resolve()
    java_home = args.java_home.resolve()
    manifest = root / "supply-chain" / "local-forge-derivative.json"
    if not wrapper.is_file() or not core_dir.is_dir() or not (java_home / "bin" / "java").is_file():
        raise ValueError("fixed wrapper, reviewed core, and Java home are required")

    base = args.cache_base.resolve() if args.cache_base is not None else None
    if base is not None:
        base.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="reality-foundation-forge-derivative-", dir=base) as temporary:
        temporary_root = Path(temporary)
        first_home = temporary_root / "first"
        first_home.mkdir()
        first_raw, first_canonical = materialize(
            root, wrapper, core_dir, java_home, first_home, manifest)
        # Retain only the exact 19 MB derivative after it has passed the
        # canonical verifier. This lets the second cache begin empty without
        # requiring two complete Gradle homes to coexist on a CI disk.
        first_jar = first_home / DERIVATIVE_RELATIVE_PATH
        preserved_first_jar = temporary_root / derivative.EXPECTED_FILE
        shutil.copyfile(first_jar, preserved_first_jar)
        if sha256(preserved_first_jar) != first_raw:
            raise ValueError("preserved first local derivative changed during cache cleanup")
        if derivative.verify(
                preserved_first_jar, derivative.EXPECTED_COMPONENT, manifest) != first_canonical:
            raise ValueError("preserved first local derivative no longer has its canonical digest")
        shutil.rmtree(first_home)
        second_home = temporary_root / "second"
        second_home.mkdir()
        second_raw, second_canonical = materialize(
            root, wrapper, core_dir, java_home, second_home, manifest)
    if first_canonical != second_canonical:
        raise ValueError("two empty-cache mapped Forge canonical digests differ")
    print("two empty Gradle homes produced the approved local Forge derivative "
          f"canonical_sha256={first_canonical} raw_sha256_first={first_raw} "
          f"raw_sha256_second={second_raw}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
