# Supply-chain verification

`gradle/verification-metadata.xml` is generated and consumed through Gradle
8.8 dependency verification in strict mode. Every downloaded input has a
committed SHA-256 allowlist entry, including the ForgeGradle plugin, Forge and
MCP artifacts fetched by ForgeGradle, JUnit, and their required dependencies.
No ignored signing key or broad trusted-artifact pattern is allowed.
`supply-chain/dependency-verification-manifest.json` records the reviewed
canonical set and count of those entries, so the repository's machine check
also rejects a syntactically valid added artifact or checksum allowlist.

## The one local derivative boundary

ForgeGradle creates this JAR locally after it has verified and transformed its
downloaded inputs:

```text
net.minecraftforge:forge:1.20.1-47.4.10_mapped_official_1.20.1
forge-1.20.1-47.4.10_mapped_official_1.20.1.jar
```

Its raw ZIP SHA-256 changes between clean Gradle homes because ForgeGradle
writes volatile ZIP timestamps. The metadata file consequently has exactly
one `trusted-artifacts/trust` entry matching all four fields above, with no
`regex` attribute. It does not trust a group, name family, version range, or
any downloaded artifact.

That narrow Gradle boundary is not sufficient by itself. The committed
`supply-chain/local-forge-derivative.json` pins a SHA-256 calculated by
`scripts/verify_local_forge_derivative.py`. The canonical digest includes:

- sorted ZIP entry paths;
- every uncompressed entry byte and archive comment;
- ZIP compression/version/flag fields, platform/mode attributes, directory
  status, and every non-timestamp extra field.

Only ZIP entry order, DOS date/time, and the timestamp-only Info-ZIP `0x5455`
extra field are excluded. Embedded signature files are ordinary uncompressed
contents and are not excluded. Duplicate paths, traversal paths, encryption,
symlinks, malformed extra fields, unexpected filename/version, entry changes,
or content changes fail closed.

The Forge task `verifyLocalForgeDerivative` runs before `JavaCompile`,
GameTest, archive/reobfuscation, and runtime-SBOM output. It resolves exactly
one mapped Forge artifact and invokes the canonical verifier. The verifier's
self-test proves changed content, an added entry, a removed entry, a changed
version, and a changed filename fail.

The runtime SBOM records this one component and its closure entry with the
same canonical content SHA and
`reality.hashBasis=reality-foundation-forge-local-derivative-canonical-v1`.
This avoids representing the volatile raw ZIP SHA as a reproducible SBOM
identity; every downloaded component remains a raw artifact SHA-256 record.

## Fresh-cache procedure

Use two newly created, otherwise empty `GRADLE_USER_HOME` directories. In each
home run a strict online Forge compile that materializes the mapped JAR, then
compare the two resulting paths. CI performs the same check with:

```sh
python3 scripts/verify_local_forge_derivative_caches.py \
  --repo-root . --core-dir ../reality-core --java-home "${JAVA_HOME}"
```

The raw ZIP SHA values may differ; the harness must report the same committed
canonical digest. After the online run, run the same strict
check with that same Gradle home and `--offline`. Removing verification
metadata, tampering a downloaded SHA, adding an unknown artifact, widening the
trust entry, or restoring a raw local-derivative checksum is rejected by
`scripts/verify_dependency_verification.py --self-test`.

Forge's `downloadAssets` task obtains Minecraft assets from the official asset
index and verifies each object against that index's content hash. CI performs
that acquisition only in its online phase, before the offline GameTest phase;
the Gradle verification metadata remains the raw-SHA allowlist for Maven and
plugin inputs.

Downloaded artifacts and the local derivative are intentionally different
trust domains: only downloaded inputs use Gradle's raw SHA-256 allowlist. The
single locally generated mapped Forge JAR uses the exact four-field Gradle
boundary plus the canonical-content verifier above; no other generated or
downloaded artifact may use that exception.
