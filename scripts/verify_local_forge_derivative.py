#!/usr/bin/env python3
"""Verify ForgeGradle's one permitted local mapped-Forge derivative.

Gradle dependency verification protects downloaded artifacts by raw SHA-256.
ForgeGradle also creates one local mapped Forge JAR whose ZIP timestamps change
between clean caches while its entries stay byte-for-byte identical.  This
verifier is the narrow, content-addressed gate for that derivative: it accepts
only the fixed component and filename recorded in the manifest and hashes
sorted entry names, stable security metadata, and every uncompressed byte.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unicodedata
import zipfile


ALGORITHM = "reality-foundation-forge-local-derivative-canonical-v1"
MAGIC = (ALGORITHM + "\x00").encode("ascii")
EXPECTED_COMPONENT = "net.minecraftforge:forge:1.20.1-47.4.10_mapped_official_1.20.1"
EXPECTED_FILE = "forge-1.20.1-47.4.10_mapped_official_1.20.1.jar"
VOLATILE_EXTRA_FIELD_IDS = {0x5455}  # Info-ZIP extended timestamp: timestamp only.
SHA256_HEX_LENGTH = 64


class DerivativeError(ValueError):
    """Raised when the narrow local-derivative contract is not met."""


def _field(digest: "hashlib._Hash", name: str, value: bytes) -> None:
    encoded_name = name.encode("ascii")
    digest.update(struct.pack(">H", len(encoded_name)))
    digest.update(encoded_name)
    digest.update(struct.pack(">Q", len(value)))
    digest.update(value)


def _normal_entry_name(name: str) -> str:
    if not name or "\x00" in name or "\\" in name or name.startswith("/"):
        raise DerivativeError(f"unsafe ZIP entry path: {name!r}")
    if unicodedata.normalize("NFC", name) != name:
        raise DerivativeError(f"non-NFC ZIP entry path: {name!r}")
    pieces = name.split("/")
    if any(piece in {"", ".", ".."} for piece in pieces[:-1]):
        raise DerivativeError(f"unsafe ZIP entry path: {name!r}")
    if name.endswith("/"):
        if pieces[-2] in {"", ".", ".."}:
            raise DerivativeError(f"unsafe ZIP directory path: {name!r}")
    elif pieces[-1] in {"", ".", ".."}:
        raise DerivativeError(f"unsafe ZIP entry path: {name!r}")
    return name


def _stable_extra(extra: bytes) -> bytes:
    """Preserve all extra metadata except the documented timestamp-only field."""

    retained: list[tuple[int, bytes]] = []
    offset = 0
    while offset < len(extra):
        if offset + 4 > len(extra):
            raise DerivativeError("malformed ZIP extra field header")
        field_id, size = struct.unpack_from("<HH", extra, offset)
        offset += 4
        if offset + size > len(extra):
            raise DerivativeError("malformed ZIP extra field payload")
        payload = extra[offset:offset + size]
        offset += size
        if field_id not in VOLATILE_EXTRA_FIELD_IDS:
            retained.append((field_id, payload))
    if offset != len(extra):
        raise DerivativeError("malformed ZIP extra field length")
    result = bytearray()
    for field_id, payload in retained:
        result.extend(struct.pack("<HH", field_id, len(payload)))
        result.extend(payload)
    return bytes(result)


def _entry_metadata(entry: zipfile.ZipInfo) -> bytes:
    if entry.flag_bits & 0x1:
        raise DerivativeError(f"encrypted ZIP entry is forbidden: {entry.filename}")
    mode = (entry.external_attr >> 16) & 0xFFFF
    if mode & 0o170000 == 0o120000:
        raise DerivativeError(f"symbolic-link ZIP entry is forbidden: {entry.filename}")
    return b"".join((
        struct.pack(">B", 1 if entry.is_dir() else 0),
        struct.pack(">B", entry.create_system),
        struct.pack(">H", entry.create_version),
        struct.pack(">H", entry.extract_version),
        struct.pack(">H", entry.compress_type),
        struct.pack(">H", entry.flag_bits),
        struct.pack(">H", entry.internal_attr),
        struct.pack(">I", entry.external_attr),
        _stable_extra(entry.extra),
    ))


def canonical_digest(path: Path) -> str:
    """Return the canonical SHA-256 for an allowed mapped Forge JAR.

    ZIP entry order, DOS timestamps, and the timestamp-only 0x5455 extra
    field are intentionally excluded. The archive comment, all other extra
    fields, ZIP compression/version/flag attributes, platform/mode attributes,
    paths, and uncompressed contents are part of the digest.
    """

    if not path.is_file():
        raise DerivativeError(f"local Forge derivative is missing: {path}")
    digest = hashlib.sha256(MAGIC)
    try:
        with zipfile.ZipFile(path, "r") as archive:
            entries: list[tuple[str, zipfile.ZipInfo]] = []
            seen: set[str] = set()
            for entry in archive.infolist():
                name = _normal_entry_name(entry.filename)
                if name in seen:
                    raise DerivativeError(f"duplicate ZIP entry path: {name}")
                seen.add(name)
                entries.append((name, entry))
            entries.sort(key=lambda pair: pair[0].encode("utf-8"))
            _field(digest, "archive-comment", archive.comment)
            _field(digest, "entry-count", struct.pack(">Q", len(entries)))
            for name, entry in entries:
                _field(digest, "entry-path", name.encode("utf-8"))
                _field(digest, "entry-metadata", _entry_metadata(entry))
                _field(digest, "entry-size", struct.pack(">Q", entry.file_size))
                with archive.open(entry, "r") as source:
                    while chunk := source.read(1024 * 1024):
                        digest.update(chunk)
    except (OSError, zipfile.BadZipFile, RuntimeError) as error:
        raise DerivativeError(f"cannot read local Forge derivative {path}: {error}") from error
    return digest.hexdigest()


def load_manifest(path: Path) -> dict[str, object]:
    if not path.is_file():
        raise DerivativeError(f"local derivative manifest is missing: {path}")
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise DerivativeError(f"local derivative manifest is invalid: {error}") from error
    if not isinstance(manifest, dict):
        raise DerivativeError("local derivative manifest must be an object")
    required = {
        "algorithm": ALGORITHM,
        "component": EXPECTED_COMPONENT,
        "file": EXPECTED_FILE,
    }
    for key, expected in required.items():
        if manifest.get(key) != expected:
            raise DerivativeError(f"local derivative manifest {key} is not the exact reviewed value")
    digest = manifest.get("canonical_sha256")
    if not isinstance(digest, str) or len(digest) != SHA256_HEX_LENGTH or any(
            character not in "0123456789abcdef" for character in digest):
        raise DerivativeError("local derivative manifest has no valid canonical SHA-256")
    exclusions = manifest.get("volatile_exclusions")
    if exclusions != [
            "ZIP entry order",
            "ZIP DOS date/time",
            "ZIP 0x5455 extended timestamp extra field",
    ]:
        raise DerivativeError("local derivative manifest has an unreviewed volatile exclusion")
    return manifest


def verify(path: Path, component: str, manifest_path: Path) -> str:
    manifest = load_manifest(manifest_path)
    if component != EXPECTED_COMPONENT:
        raise DerivativeError(f"unexpected local derivative component: {component}")
    if path.name != EXPECTED_FILE:
        raise DerivativeError(f"unexpected local derivative filename: {path.name}")
    actual = canonical_digest(path)
    if actual != manifest["canonical_sha256"]:
        raise DerivativeError(
            "local Forge derivative canonical SHA-256 mismatch: "
            f"expected {manifest['canonical_sha256']}, got {actual}")
    return actual


def _write_fixture(path: Path, entries: list[tuple[str, bytes]], timestamp: int) -> None:
    extra = struct.pack("<HHBI", 0x5455, 5, 1, timestamp)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.comment = b"reviewed fixture comment"
        for name, content in entries:
            info = zipfile.ZipInfo(name, date_time=(2026, 8, 9, 0, 0, 0))
            info.extra = extra
            info.external_attr = 0o100644 << 16
            archive.writestr(info, content)


def _fixture_manifest(path: Path, digest: str) -> Path:
    manifest = path / "local-forge-derivative.json"
    manifest.write_text(json.dumps({
        "algorithm": ALGORITHM,
        "component": EXPECTED_COMPONENT,
        "file": EXPECTED_FILE,
        "canonical_sha256": digest,
        "volatile_exclusions": [
            "ZIP entry order",
            "ZIP DOS date/time",
            "ZIP 0x5455 extended timestamp extra field",
        ],
    }, indent=2) + "\n", encoding="utf-8")
    return manifest


def _expect_failure(callback, description: str) -> None:
    try:
        callback()
    except DerivativeError:
        return
    raise AssertionError(f"negative local derivative fixture was accepted: {description}")


def self_test() -> None:
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        entries = [("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n"),
                   ("net/minecraftforge/Example.class", b"class bytes")]
        first = root / EXPECTED_FILE
        second_dir = root / "second"
        second_dir.mkdir()
        second = second_dir / EXPECTED_FILE
        _write_fixture(first, entries, 1)
        _write_fixture(second, list(reversed(entries)), 2)
        first_digest = canonical_digest(first)
        assert first_digest == canonical_digest(second), "volatile ZIP data changed canonical digest"
        manifest = _fixture_manifest(root, first_digest)
        assert verify(first, EXPECTED_COMPONENT, manifest) == first_digest
        assert verify(second, EXPECTED_COMPONENT, manifest) == first_digest

        modified_dir = root / "modified"
        modified_dir.mkdir()
        modified = modified_dir / EXPECTED_FILE
        _write_fixture(modified, [(entries[0][0], b"changed"), entries[1]], 3)
        _expect_failure(lambda: verify(modified, EXPECTED_COMPONENT, manifest), "content mutation")

        added_dir = root / "added"
        added_dir.mkdir()
        added = added_dir / EXPECTED_FILE
        _write_fixture(added, entries + [("extra.txt", b"unexpected")], 4)
        _expect_failure(lambda: verify(added, EXPECTED_COMPONENT, manifest), "entry addition")

        removed_dir = root / "removed"
        removed_dir.mkdir()
        removed = removed_dir / EXPECTED_FILE
        _write_fixture(removed, entries[:1], 5)
        _expect_failure(lambda: verify(removed, EXPECTED_COMPONENT, manifest), "entry removal")

        _expect_failure(lambda: verify(first, "net.minecraftforge:forge:wrong", manifest), "other version")
        wrong_name = root / "forge-other.jar"
        wrong_name.write_bytes(first.read_bytes())
        _expect_failure(lambda: verify(wrong_name, EXPECTED_COMPONENT, manifest), "other filename")
    print("local Forge derivative negative harness self-test passed "
          "(content/add/remove/version/filename cases)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--component", default=EXPECTED_COMPONENT)
    parser.add_argument("--manifest", type=Path,
                        default=Path("supply-chain") / "local-forge-derivative.json")
    parser.add_argument("--compare", nargs=2, type=Path, metavar=("FIRST", "SECOND"))
    parser.add_argument("--print-digest", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.compare:
        first = verify(args.compare[0], args.component, args.manifest)
        second = verify(args.compare[1], args.component, args.manifest)
        if first != second:
            raise DerivativeError("clean-cache local Forge derivative canonical digests differ")
        print(f"local Forge derivative canonical digest matches across both inputs: {first}")
        return 0
    if args.jar is None:
        parser.error("--jar is required unless --self-test or --compare is used")
    if args.print_digest:
        if args.component != EXPECTED_COMPONENT or args.jar.name != EXPECTED_FILE:
            raise DerivativeError("--print-digest accepts only the exact local mapped Forge derivative")
        print(canonical_digest(args.jar))
        return 0
    print(f"local Forge derivative canonical digest passed: {verify(args.jar, args.component, args.manifest)}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DerivativeError as error:
        print(f"local Forge derivative verification failed: {error}")
        raise SystemExit(2)
