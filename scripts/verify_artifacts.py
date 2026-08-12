#!/usr/bin/env python3
"""Verify Java 17 bytecode, Forge Jar-in-Jar metadata, and class uniqueness."""

from __future__ import annotations

import argparse
import io
import json
import zipfile
from collections import defaultdict
from pathlib import Path


EXPECTED_JARS = {
    "reality-foundation-api": "0.1.0-SNAPSHOT",
    "reality-core": "0.1.0-SNAPSHOT",
}


def class_major(data: bytes) -> int:
    if len(data) < 8 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("invalid class file")
    return int.from_bytes(data[6:8], "big")


def inspect_layer(data: bytes, label: str, classes: dict[str, list[str]], layers: list[tuple[str, bytes]]) -> None:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in archive.namelist():
            if name.endswith(".class"):
                if class_major(archive.read(name)) != 61:
                    raise ValueError(f"non-Java-17 class: {label}!{name}")
                classes[name].append(f"{label}!{name}")
            if name.endswith(".jar") and name.startswith("META-INF/jarjar/"):
                nested = archive.read(name)
                layers.append((name, nested))


def verify(path: Path) -> None:
    classes: dict[str, list[str]] = defaultdict(list)
    layers: list[tuple[str, bytes]] = []
    data = path.read_bytes()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = set(archive.namelist())
        if "META-INF/mods.toml" not in names:
            raise ValueError(f"missing mods.toml in {path}")
        mods_toml = archive.read("META-INF/mods.toml").decode("utf-8")
        if 'modId="reality_foundation"' not in mods_toml:
            raise ValueError("mod id is missing from mods.toml")
        if 'version="0.1.0-SNAPSHOT"' not in mods_toml:
            raise ValueError("mod version is not 0.1.0-SNAPSHOT")
        if "47.4.22" in mods_toml:
            raise ValueError("forbidden Forge candidate appears in mods.toml")
        metadata_name = "META-INF/jarjar/metadata.json"
        if metadata_name not in names:
            raise ValueError("missing Forge Jar-in-Jar metadata")
        metadata = json.loads(archive.read(metadata_name))
        jars = metadata.get("jars", [])
        if len(jars) != len(EXPECTED_JARS):
            raise ValueError("unexpected Jar-in-Jar dependency count")
        seen: set[str] = set()
        for item in jars:
            artifact = item["identifier"]["artifact"]
            if artifact == "foundation-api":
                raise ValueError("obsolete foundation-api artifact coordinate is forbidden")
            version = item["version"]["artifactVersion"]
            if artifact not in EXPECTED_JARS or EXPECTED_JARS[artifact] != version:
                raise ValueError(f"unexpected Jar-in-Jar dependency: {artifact}:{version}")
            if item["version"]["range"] != f"[{version}]":
                raise ValueError(f"Jar-in-Jar dependency is not singleton-pinned: {artifact}")
            nested_name = item["path"]
            if nested_name not in names:
                raise ValueError(f"metadata points to missing nested jar: {nested_name}")
            seen.add(artifact)
        if seen != set(EXPECTED_JARS):
            raise ValueError("Jar-in-Jar dependency set is incomplete")
    inspect_layer(data, str(path), classes, layers)
    for label, nested in layers:
        inspect_layer(nested, label, classes, layers=[])
    duplicates = {name: locations for name, locations in classes.items() if len(locations) != 1}
    if duplicates:
        raise ValueError(f"duplicate classes across runtime layers: {duplicates}")
    if not any(name.startswith("io/github/yu1sh/reality/foundation/api/") for name in classes):
        raise ValueError("reality-foundation-api classes are not bundled")
    if not any(name.startswith("io/github/yu1sh/reality/")
               and not name.startswith("io/github/yu1sh/reality/foundation/")
               for name in classes):
        raise ValueError("reality-core classes are not bundled")
    print(f"artifact passed: {path} ({len(classes)} unique classes)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("jars", nargs="+", type=Path)
    args = parser.parse_args()
    for jar in args.jars:
        verify(jar)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
