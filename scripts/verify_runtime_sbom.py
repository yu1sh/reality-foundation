#!/usr/bin/env python3
"""Validate the role-aware deterministic runtime SBOM."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile


LOCAL_FORGE_COMPONENT = (
    "net.minecraftforge", "forge", "1.20.1-47.4.10_mapped_official_1.20.1")
LOCAL_FORGE_FILE = "forge-1.20.1-47.4.10_mapped_official_1.20.1.jar"
LOCAL_FORGE_CANONICAL_ALGORITHM = "reality-foundation-forge-local-derivative-canonical-v1"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def properties(component: dict) -> dict[str, str]:
    return {item["name"]: item["value"] for item in component.get("properties", [])}


def nested_hashes(mod_jar: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    with zipfile.ZipFile(mod_jar) as archive:
        metadata = json.loads(archive.read("META-INF/jarjar/metadata.json"))
        if len(metadata.get("jars", [])) != 2:
            raise ValueError("final Jar-in-Jar metadata must contain exactly two entries")
        for item in metadata["jars"]:
            artifact = item["identifier"]["artifact"]
            path = item["path"]
            data = archive.read(path)
            result[artifact] = hashlib.sha256(data).hexdigest()
            if item["version"]["range"] != f"[{item['version']['artifactVersion']}]":
                raise ValueError(f"non-singleton Jar-in-Jar range for {artifact}")
    return result


def local_forge_canonical_digest(manifest_path: Path) -> str:
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"local Forge derivative manifest is unreadable: {error}") from error
    expected = {
        "algorithm": LOCAL_FORGE_CANONICAL_ALGORITHM,
        "component": ":".join(LOCAL_FORGE_COMPONENT),
        "file": LOCAL_FORGE_FILE,
    }
    if not isinstance(manifest, dict) or any(manifest.get(key) != value for key, value in expected.items()):
        raise ValueError("local Forge derivative manifest does not describe the exact reviewed component")
    digest = manifest.get("canonical_sha256")
    if not isinstance(digest, str) or len(digest) != 64 or any(character not in "0123456789abcdef" for character in digest):
        raise ValueError("local Forge derivative manifest has no canonical SHA-256")
    return digest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("sbom", type=Path)
    parser.add_argument("--mod-jar", type=Path)
    parser.add_argument("--api-jar", type=Path)
    parser.add_argument("--core-jar", type=Path)
    parser.add_argument("--local-derivative-manifest", type=Path,
                        default=Path("supply-chain") / "local-forge-derivative.json")
    args = parser.parse_args()
    document = json.loads(args.sbom.read_text(encoding="utf-8"))
    if document.get("bomFormat") != "CycloneDX" or document.get("specVersion") != "1.5":
        raise ValueError("SBOM format/specification is not CycloneDX 1.5")
    actual: dict[tuple[str, str, str], dict] = {}
    for component in document.get("components", []):
        if component.get("name") == "foundation-api":
            raise ValueError("obsolete foundation-api SBOM coordinate is forbidden")
        key = (component.get("group"), component.get("name"), component.get("version"))
        if key in actual:
            raise ValueError(f"duplicate SBOM component: {key}")
        actual[key] = component
        if len(component.get("hashes", [])) != 1:
            raise ValueError(f"component has no single SHA-256 hash: {key}")
        if component["hashes"][0].get("alg") != "SHA-256" or len(
                component["hashes"][0].get("content", "")) != 64:
            raise ValueError(f"component hash is invalid: {key}")
        if not component.get("licenses"):
            raise ValueError(f"component has no license reference: {key}")
        item = properties(component)
        if item.get("reality.licenseQualification") != "reference-recorded":
            raise ValueError(f"license qualification is not fail-closed: {key}")
    required = {
        ("io.github.yu1sh.reality", "reality-foundation-api", "0.1.0-SNAPSHOT"),
        ("io.github.yu1sh.reality", "reality-core", "0.1.0-SNAPSHOT"),
    }
    if not required.issubset(actual):
        raise ValueError(f"bundled component set is incomplete: {required - set(actual)}")
    forge = [key for key in actual if key[0] == "net.minecraftforge" and key[1] == "forge"]
    if forge != [LOCAL_FORGE_COMPONENT]:
        raise ValueError("exact Forge 47.4.10 resolved component is missing")
    canonical_forge_digest = local_forge_canonical_digest(args.local_derivative_manifest)
    minecraft = [key for key in actual if key[0] == "net.minecraft"]
    if not minecraft:
        raise ValueError("resolved Minecraft component is missing")
    for key, component in actual.items():
        if component.get("scope") != "required":
            raise ValueError(f"component has incorrect CycloneDX scope: {key}")
        item = properties(component)
        if key in required:
            if (item.get("reality.role"), item.get("reality.bundled"),
                    item.get("reality.configurations")) != (
                        "bundled-jar-in-jar", "true", "compile,runtime"):
                raise ValueError(f"bundled component role mismatch: {key}")
        elif key == forge[0]:
            if item.get("reality.role") != "provided-forge-runtime":
                raise ValueError("Forge component role mismatch")
            if (item.get("reality.hashBasis") != LOCAL_FORGE_CANONICAL_ALGORITHM
                    or item.get("reality.canonicalContentSha256") != canonical_forge_digest
                    or component["hashes"][0]["content"] != canonical_forge_digest):
                raise ValueError("Forge SBOM component must use the reviewed canonical local-derivative digest")
        elif key[0] == "net.minecraft":
            if item.get("reality.role") != "provided-minecraft-runtime":
                raise ValueError(f"Minecraft component role mismatch: {key}")
        else:
            raise ValueError(f"unexpected component outside owned boundary: {key}")

    closure = document.get("x-reality-runtime-closure", [])
    if not closure or len(closure) < len(actual):
        raise ValueError("resolved runtime closure is absent or incomplete")
    closure_keys = set()
    for record in closure:
        if record.get("name") == "foundation-api":
            raise ValueError("obsolete foundation-api runtime closure coordinate is forbidden")
        key = (record.get("group"), record.get("name"), record.get("version"), record.get("file"))
        if key in closure_keys or len(record.get("sha256", "")) != 64:
            raise ValueError(f"runtime closure record is duplicate or unhashed: {key}")
        closure_keys.add(key)
        if record.get("ownership") not in {
                "this-repo-bundled", "formal-forge-minecraft-distribution-sbom"}:
            raise ValueError(f"runtime closure ownership is unknown: {key}")
    forge_closure = [record for record in closure if (
        record.get("group"), record.get("name"), record.get("version"), record.get("file"))
        == (*LOCAL_FORGE_COMPONENT, LOCAL_FORGE_FILE)]
    if len(forge_closure) != 1 or (
            forge_closure[0].get("sha256") != canonical_forge_digest
            or forge_closure[0].get("hashBasis") != LOCAL_FORGE_CANONICAL_ALGORITHM):
        raise ValueError("Forge runtime closure must use the reviewed canonical local-derivative digest")

    root = document["metadata"]["component"]
    if root.get("licenses", [{}])[0].get("license", {}).get("name") != "Apache-2.0":
        raise ValueError("root component is not identified as Apache-2.0")
    if args.mod_jar:
        if sha256(args.mod_jar) != root["hashes"][0]["content"]:
            raise ValueError("SBOM root hash does not match final mod jar")
        nested = nested_hashes(args.mod_jar)
        for artifact, source in (("reality-foundation-api", args.api_jar), ("reality-core", args.core_jar)):
            if source is None:
                raise ValueError(f"source artifact required for {artifact}")
            item = properties(actual[next(key for key in actual if key[1] == artifact)])
            if nested.get(artifact) != item.get("reality.nestedJarEntrySha256"):
                raise ValueError(f"nested entry hash missing for {artifact}")
            if sha256(source) != item.get("reality.sourceArtifactSha256"):
                raise ValueError(f"source artifact hash mismatch for {artifact}")
    print("runtime SBOM passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
