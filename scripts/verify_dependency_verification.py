#!/usr/bin/env python3
"""Machine-check the committed Gradle dependency-verification allowlist."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import tempfile
import xml.etree.ElementTree as element_tree


NAMESPACE = "https://schema.gradle.org/dependency-verification"
QUALIFIED = lambda name: f"{{{NAMESPACE}}}{name}"
SHA256 = re.compile(r"[0-9a-f]{64}")
REQUIRED_COMPONENTS = {
    ("net.minecraftforge.gradle", "ForgeGradle", "6.0.54"),
    ("net.minecraftforge", "forge", "1.20.1-47.4.10_mapped_official_1.20.1"),
    ("org.junit.jupiter", "junit-jupiter", "5.11.4"),
}
LOCAL_DERIVATIVE_COMPONENT = (
    "net.minecraftforge", "forge", "1.20.1-47.4.10_mapped_official_1.20.1")
LOCAL_DERIVATIVE_FILE = "forge-1.20.1-47.4.10_mapped_official_1.20.1.jar"
LOCAL_DERIVATIVE_REASON = (
    "ForgeGradle local mapped derivative; verifyLocalForgeDerivative pins canonical contents")


def validate_local_derivative_trust(configuration: element_tree.Element) -> None:
    trusted = configuration.find(QUALIFIED("trusted-artifacts"))
    if trusted is None:
        raise ValueError("exact local mapped-Forge trusted-artifact boundary is missing")
    entries = trusted.findall(QUALIFIED("trust"))
    if len(entries) != 1:
        raise ValueError("exactly one local mapped-Forge trusted artifact is permitted")
    trust = entries[0]
    expected = {
        "group": LOCAL_DERIVATIVE_COMPONENT[0],
        "name": LOCAL_DERIVATIVE_COMPONENT[1],
        "version": LOCAL_DERIVATIVE_COMPONENT[2],
        "file": LOCAL_DERIVATIVE_FILE,
        "reason": LOCAL_DERIVATIVE_REASON,
    }
    if trust.attrib != expected:
        raise ValueError("trusted artifact must be the exact local mapped-Forge group/name/version/file")
    if trust.get("regex") is not None:
        raise ValueError("regex trusted-artifact boundaries are forbidden")


def canonical_metadata_digest(
        configuration: element_tree.Element,
        records: list[tuple[str, str, str, str, tuple[str, ...]]]) -> str:
    """Digest the reviewed allowlist semantics, not XML whitespace/order."""

    payload = {
        "verify_metadata": configuration.findtext(QUALIFIED("verify-metadata")),
        "verify_signatures": configuration.findtext(QUALIFIED("verify-signatures")),
        "trusted_artifacts": [dict(sorted(item.attrib.items()))
                              for item in configuration.findall(
                                      f"{QUALIFIED('trusted-artifacts')}/{QUALIFIED('trust')}")],
        "artifacts": [
            {"group": group, "name": name, "version": version, "file": artifact,
             "sha256": list(checksums)}
            for group, name, version, artifact, checksums in sorted(records)
        ],
    }
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def verify_manifest(path: Path, artifact_count: int, canonical_sha256: str) -> None:
    if not path.is_file():
        raise ValueError("dependency verification manifest is missing")
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"dependency verification manifest is invalid: {error}") from error
    expected = {
        "format": "reality-foundation-dependency-verification-manifest-v1",
        "artifact_sha256_count": artifact_count,
        "canonical_sha256": canonical_sha256,
    }
    if manifest != expected:
        raise ValueError("dependency verification allowlist differs from the reviewed manifest")


def inspect_metadata(path: Path) -> tuple[int, str]:
    if not path.is_file():
        raise ValueError("Gradle verification metadata is missing")
    root = element_tree.parse(path).getroot()
    if root.tag != QUALIFIED("verification-metadata"):
        raise ValueError("verification metadata namespace is invalid")
    configuration = root.find(QUALIFIED("configuration"))
    if configuration is None or configuration.findtext(QUALIFIED("verify-metadata")) != "true":
        raise ValueError("Gradle metadata verification is not enabled")
    validate_local_derivative_trust(configuration)
    if configuration.find(QUALIFIED("ignored-keys")) is not None:
        raise ValueError("ignored signing keys are forbidden")
    components = root.find(QUALIFIED("components"))
    if components is None:
        raise ValueError("verification metadata has no components")
    seen_components: set[tuple[str, str, str]] = set()
    artifact_count = 0
    records: list[tuple[str, str, str, str, tuple[str, ...]]] = []
    for component in components.findall(QUALIFIED("component")):
        identity = (component.get("group", ""), component.get("name", ""), component.get("version", ""))
        if not all(identity):
            raise ValueError("verification metadata has an anonymous component")
        if identity in seen_components:
            raise ValueError(f"verification metadata repeats component {identity}")
        seen_components.add(identity)
        artifacts = component.findall(QUALIFIED("artifact"))
        if not artifacts:
            raise ValueError(f"component has no artifact allowlist: {identity}")
        for artifact in artifacts:
            if not artifact.get("name"):
                raise ValueError(f"component has unnamed artifact: {identity}")
            if identity == LOCAL_DERIVATIVE_COMPONENT and artifact.get("name") == LOCAL_DERIVATIVE_FILE:
                raise ValueError("local mapped Forge JAR must use only the exact trusted-artifact/custom verifier boundary")
            checksums = artifact.findall(QUALIFIED("sha256"))
            if not checksums or any(not SHA256.fullmatch(item.get("value", "")) for item in checksums):
                raise ValueError(f"artifact has no valid SHA-256 allowlist: {identity}:{artifact.get('name')}")
            records.append((*identity, artifact.get("name"),
                            tuple(sorted(item.get("value", "") for item in checksums))))
            artifact_count += 1
    missing = REQUIRED_COMPONENTS - seen_components
    if missing:
        raise ValueError(f"required ForgeGradle/Forge/JUnit entries are absent: {sorted(missing)}")
    local_component = next(
        component for component in components.findall(QUALIFIED("component"))
        if (component.get("group"), component.get("name"), component.get("version"))
        == LOCAL_DERIVATIVE_COMPONENT)
    local_pom = f"{LOCAL_DERIVATIVE_COMPONENT[1]}-{LOCAL_DERIVATIVE_COMPONENT[2]}.pom"
    if not any(artifact.get("name") == local_pom for artifact in local_component.findall(QUALIFIED("artifact"))):
        raise ValueError("mapped Forge component has no downloaded POM SHA-256 allowlist")
    return artifact_count, canonical_metadata_digest(configuration, records)


def validate_metadata(path: Path, manifest_path: Path | None = None) -> int:
    artifact_count, canonical_sha256 = inspect_metadata(path)
    if manifest_path is not None:
        verify_manifest(manifest_path, artifact_count, canonical_sha256)
    return artifact_count


def manifest_text(path: Path) -> str:
    artifact_count, canonical_sha256 = inspect_metadata(path)
    return json.dumps({
        "format": "reality-foundation-dependency-verification-manifest-v1",
        "artifact_sha256_count": artifact_count,
        "canonical_sha256": canonical_sha256,
    }, indent=2) + "\n"


def fixture() -> str:
    component_xml = []
    for group, name, version in sorted(REQUIRED_COMPONENTS):
        artifact_name = (f"{name}-{version}.pom" if (group, name, version) == LOCAL_DERIVATIVE_COMPONENT
                         else f"{name}.jar")
        component_xml.append(
            """      <component group=\"{group}\" name=\"{name}\" version=\"{version}\">
         <artifact name=\"{artifact}\"><sha256 value=\"{sha}\"/></artifact>
      </component>""".format(group=group, name=name, version=version,
                                artifact=artifact_name, sha="a" * 64))
    components = "\n".join(component_xml)
    return f"""<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<verification-metadata xmlns=\"{NAMESPACE}\">
   <configuration>
      <verify-metadata>true</verify-metadata>
      <trusted-artifacts>
         <trust group=\"{LOCAL_DERIVATIVE_COMPONENT[0]}\" name=\"{LOCAL_DERIVATIVE_COMPONENT[1]}\" version=\"{LOCAL_DERIVATIVE_COMPONENT[2]}\" file=\"{LOCAL_DERIVATIVE_FILE}\" reason=\"{LOCAL_DERIVATIVE_REASON}\"/>
      </trusted-artifacts>
   </configuration>
   <components>
{components}
   </components>
</verification-metadata>
"""


def expect_failure(callback, description: str) -> None:
    try:
        callback()
    except ValueError:
        return
    raise AssertionError(f"negative verification fixture was accepted: {description}")


def self_test() -> None:
    with tempfile.TemporaryDirectory() as temporary:
        path = Path(temporary) / "verification-metadata.xml"
        path.write_text(fixture(), encoding="utf-8")
        manifest = Path(temporary) / "dependency-verification-manifest.json"
        manifest.write_text(manifest_text(path), encoding="utf-8")
        assert validate_metadata(path, manifest) == len(REQUIRED_COMPONENTS)
        expect_failure(lambda: validate_metadata(path.with_name("missing.xml"), manifest),
                       "metadata deletion")

        tampered = Path(temporary) / "tampered.xml"
        tampered.write_text(fixture().replace("a" * 64, "not-a-sha", 1), encoding="utf-8")
        expect_failure(lambda: validate_metadata(tampered, manifest), "checksum tamper")

        broad = Path(temporary) / "broad.xml"
        broad.write_text(fixture().replace(
            f'file=\"{LOCAL_DERIVATIVE_FILE}\"', 'file=\"forge-.*\\\\.jar\" regex=\"true\"', 1),
            encoding="utf-8")
        expect_failure(lambda: validate_metadata(broad, manifest), "broad trusted artifact")

        raw_derivative = Path(temporary) / "raw-derivative.xml"
        raw_derivative.write_text(fixture().replace(
            f'<artifact name=\"forge-{LOCAL_DERIVATIVE_COMPONENT[2]}.pom\">',
            f'<artifact name=\"{LOCAL_DERIVATIVE_FILE}\"><sha256 value=\"{"b" * 64}\"/></artifact>\n'
            f'         <artifact name=\"forge-{LOCAL_DERIVATIVE_COMPONENT[2]}.pom\">', 1),
            encoding="utf-8")
        expect_failure(lambda: validate_metadata(raw_derivative, manifest), "raw local derivative checksum")

        unknown = Path(temporary) / "unknown.xml"
        unknown.write_text(fixture().replace(
            "</components>", "      <component group=\"fixture\" name=\"unknown\" version=\"1\">"
            f"<artifact name=\"unknown.jar\"><sha256 value=\"{'c' * 64}\"/></artifact>"
            "</component>\n   </components>"), encoding="utf-8")
        expect_failure(lambda: validate_metadata(unknown, manifest), "unknown artifact with checksum")
    print("dependency verification negative harness self-test passed "
          "(missing/tampered/broad/raw-derivative/unknown cases)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata", type=Path, default=Path("gradle") / "verification-metadata.xml")
    parser.add_argument("--manifest", type=Path,
                        default=Path("supply-chain") / "dependency-verification-manifest.json")
    parser.add_argument("--print-manifest", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.print_manifest:
        print(manifest_text(args.metadata), end="")
        return 0
    artifact_count = validate_metadata(args.metadata, args.manifest)
    print(f"Gradle dependency verification metadata passed: {artifact_count} artifact SHA-256 allowlist entries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
