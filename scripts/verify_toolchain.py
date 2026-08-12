#!/usr/bin/env python3
"""Validate exact release-train inputs and reject relaxed Java selectors."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


EXPECTED_JAVA = "17.0.20+8"
EXPECTED_ARCHIVE = "be7668bc030d578b83d6d5ef9221d6d6729bbbca8cf94a7d52e16ac68b5a5a35"
JAVA_SELECTOR = re.compile(r"java-version:\s*(['\"])" + re.escape(EXPECTED_JAVA) + r"\1")


def validate_workflow(text: str) -> None:
    selectors = re.findall(r"java-version:\s*(['\"])(.*?)\1", text)
    if selectors != [("'", EXPECTED_JAVA)] and selectors != [("\"", EXPECTED_JAVA)]:
        raise ValueError("workflow must contain exactly one exact Temurin Java selector")
    if not JAVA_SELECTOR.search(text):
        raise ValueError("workflow Java selector is not exact")
    if "distribution: temurin" not in text or "architecture: x64" not in text:
        raise ValueError("workflow must select Temurin Linux x64")
    if "17.0.20+8" not in text:
        raise ValueError("workflow must record the exact JDK build")
    if "java.runtime.version" not in text or "java.vendor" not in text:
        raise ValueError("workflow must verify exact runtime and vendor properties")
    if re.search(r"grep\s+-c\s+['\"]17\.0\.20['\"]", text):
        raise ValueError("workflow must not count repeated java -version lines")
    if "repository: yu1sh/reality-core" not in text:
        raise ValueError("workflow must checkout the reviewed reality-core repository")
    if "path: _deps/reality-core" not in text:
        raise ValueError("workflow core checkout must be isolated under _deps/reality-core")
    expected_ref = "fd93d533ba3e9aa63e182a4d0ba9da0e82b24728"
    if f"ref: {expected_ref}" not in text:
        raise ValueError("workflow core checkout ref is not exact")
    if text.count("-PrealityCoreDir=_deps/reality-core") < 1:
        raise ValueError("workflow Gradle invocations must pass the exact core checkout")


def validate_manifest(manifest: dict) -> None:
    java = manifest["java"]
    expected = {
        "vendor": "Eclipse Temurin",
        "version": EXPECTED_JAVA,
        "os": "linux",
        "architecture": "x64",
        "archive_sha256": EXPECTED_ARCHIVE,
        "class_major": 61,
    }
    for key, value in expected.items():
        if java.get(key) != value:
            raise ValueError(f"manifest java.{key} is not fixed to the accepted value")
    if manifest["gradle"]["version"] != "8.8":
        raise ValueError("Gradle must be exactly 8.8")
    forge = manifest["forge"]
    if forge["minecraft"] != "1.20.1" or forge["forge"] != "47.4.10":
        raise ValueError("Minecraft/Forge versions are not exact")
    if forge["forge_gradle"] != "6.0.54":
        raise ValueError("ForgeGradle must be exactly 6.0.54")


def self_test() -> None:
    for selector in ("17", "17.x", "latest", "17.0.20"):
        try:
            validate_workflow("java-version: '" + selector + "'\n")
        except (ValueError, KeyError):
            continue
        raise AssertionError(f"relaxed Java selector was accepted: {selector}")
    valid = (
        "java-version: '17.0.20+8'\n"
        "distribution: temurin\narchitecture: x64\n"
        "java.runtime.version\njava.vendor\n"
        "repository: yu1sh/reality-core\npath: _deps/reality-core\n"
        "ref: fd93d533ba3e9aa63e182a4d0ba9da0e82b24728\n"
        "gradle -PrealityCoreDir=_deps/reality-core\n")
    validate_workflow(valid)
    for missing in (
            "repository: yu1sh/reality-core", "path: _deps/reality-core",
            "ref: fd93d533ba3e9aa63e182a4d0ba9da0e82b24728",
            "-PrealityCoreDir=_deps/reality-core"):
        candidate = valid.replace(missing, "")
        try:
            validate_workflow(candidate)
        except ValueError:
            continue
        raise AssertionError(f"missing core checkout contract was accepted: {missing}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    root = args.repo_root.resolve()
    if args.self_test:
        self_test()
    manifest = json.loads((root / "supply-chain" / "toolchain-manifest.json").read_text())
    validate_manifest(manifest)
    validate_workflow((root / ".github" / "workflows" / "ci.yml").read_text())
    properties = (root / "gradle.properties").read_text()
    if "forge_version=47.4.22" in properties or "forge_version=47.4.22" in (root / "README.md").read_text():
        raise ValueError("candidate Forge 47.4.22 is forbidden")
    print("toolchain policy passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
