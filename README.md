# Reality Foundation

Reality Foundation is the first RT1 vertical slice for the RealityMinecraft
server. It provides a Minecraft-independent `reality-foundation-api` module and a
Forge 1.20.1 adapter named `reality_foundation` at version `0.1.0-SNAPSHOT`.
It deliberately contains no time, life, economy, jobs, shops, property, NPC,
region, audit persistence, database, block, item, entity, or world-registry
feature.

## Fixed release train

- Minecraft Java Edition 1.20.1 and official 1.20.1 mappings
- pure Forge Dedicated Server 47.4.10
- ForgeGradle 6.0.54
- Gradle 8.8, with the distribution SHA recorded in `supply-chain/`
- Eclipse Temurin JDK 17.0.20+8, Linux x64, class major 61
- `reality-core` source ref `aa9956e03a8d163d1d0a2bbdcf38cc328fc37397`
- network protocol `1`, API schema `foundation.api.v1`, release train `rt1-foundation`

The JDK archive SHA in `supply-chain/toolchain-manifest.json` is acquisition
evidence for the verified archive. It does not claim that an
`actions/setup-java` internal cache was independently verified. Local builds
provide `JAVA_HOME` through an environment variable pointing at the exact
Temurin installation; no local machine path is part of this repository.

## User path

1. On login, the client sends the fixed handshake. The server accepts only an
   exact protocol, API schema, mod version, and release train.
2. After acceptance, F8 sends a server-validated open request. The same path
   can be reached from the native menu integration.
3. The server authenticates the `ServerPlayer`, allocates a server-issued
   `SessionId`, and sends an initial snapshot with a monotonic `Revision`.
4. The native **System Status** screen shows public connection/protocol/train
   state and redacted service health. It has refresh and close controls, no
   HUD, and no per-tick full synchronization.
5. Permission level 2 or higher may receive an admin diagnostics projection.
   Permission and streamer-mode decisions are server-owned. A permission
   downgrade or streamer-mode transition removes every admin key before the
   client can apply the delta.
6. Refresh validates actor, session, expiry, rate limit, protocol, and exact
   revision. Only a forward delta is accepted; replayed or out-of-order
   deltas are rejected without changing client state.

The screen supports `en-US` and `ja-JP`, keyboard-focusable buttons, status
text in addition to color, and a compact layout that tolerates larger text.
No coordinate, JDBC detail, host, filesystem path, secret, or private player
field is included in a public projection. Streamer mode hides admin details.

## Server commands

- `/realityfoundation status` uses the same `FoundationDiagnosticsQuery` as
  the GUI and displays only the caller's server-authorized projection.
- `/realityfoundation recovery clear-sessions` is a permission-level-4
  recovery operation. Its shared application path accepts a complete
  `FoundationMutationEnvelope` (`requestId`, `operationId`, `sessionId`, and
  exact `expectedVersion`) plus the server-authenticated actor; it never uses
  a client permission field. The active server-issued session, actor,
  lifecycle, revision, permission projection, and clock are rechecked before
  commit. Same operation ID plus the same fingerprint replays the recorded
  result without clearing a newer session; a different fingerprint returns
  `OPERATION_CONFLICT`. The bounded in-memory replay ledger is deliberately
  not durable across a server restart.

`RECORDED` is the only disposition that means the audit event was recorded.
The current `NoopAuditPort` returns `NOT_CONFIGURED` and is development-only:
that result is visible in command output and never claimed as successful
persistence. A rejected, null, or throwing audit callback cannot clear state.
The audit service is intentionally not implemented in this module.

## Lifecycle and rollback

`RealityServerContextManager` stores one context per `MinecraftServer` object.
Server starting creates it; stopping removes it from the identity map before
closing diagnostics and services. Logout invalidates the actor's active
session. A server menu owns only its server-issued actor/session pair and
invalidates that exact pair once from `removed`; an old menu cannot remove a
replacement session. In-flight query callbacks are committed only after the
reservation and lifecycle are revalidated.

There is no database migration and no world registry migration. Rollback is:

1. stop the server cleanly and retain the existing world;
2. remove the `reality_foundation` mod and its server-side configuration;
3. remove the per-server context/packet/menu registration from the deployed
   mod version or restore the prior mod artifact;
4. start the unchanged world with the prior server pack.

No block, item, entity, or persistent world ID is introduced by this slice.

## Build and verification

Use an environment variable for the local exact JDK and Gradle 8.8
installation:

```sh
export JAVA_HOME="${TEMURIN_17_HOME}"
export REALITY_GRADLE_HOME="${GRADLE_8_8_HOME}"
./gradlew --dependency-verification=strict --no-daemon clean check
./gradlew --dependency-verification=strict --offline --no-daemon clean check
./gradlew --dependency-verification=strict --offline --no-daemon :forge-1.20.1:runGameTestServer
python3 scripts/verify_dependency_verification.py
python3 scripts/verify_local_forge_derivative.py --self-test
python3 scripts/run_server_smoke.py --repo-root . --gradle-command ./gradlew --java-home "${JAVA_HOME}"
```

The reviewed `reality-core` checkout is required as a sibling source
checkout, or can be selected with `-PrealityCoreDir=...`. The custom launcher
accepts only Gradle 8.8 and verifies the recorded distribution SHA when it
downloads the distribution.

`gradle/verification-metadata.xml` is the strict Gradle SHA-256 allowlist for
all downloaded inputs, including ForgeGradle, Forge/MCP inputs, and JUnit.
One exact local ForgeGradle derivative is the sole exception to a raw archive
SHA: `verifyLocalForgeDerivative` checks its canonical content SHA before
Java compilation, GameTest, archive/reobfuscation, and SBOM generation. See
[`SUPPLY_CHAIN.md`](SUPPLY_CHAIN.md) for the exact coordinate, bounded trust
entry, canonicalization rule, and two-empty-cache procedure.

The runtime SBOM labels the mapped Forge derivative with
`reality.hashBasis=reality-foundation-forge-local-derivative-canonical-v1` and
records that same canonical content SHA instead of its volatile raw ZIP SHA.
Every downloaded component continues to use its raw artifact SHA-256.

`runGameTestServer` is not the same test as the dedicated-server smoke. The
GameTest run enables the fixed `reality_foundation` namespace and must report
the exact `FoundationGameTests.contextRequestMenuPermissionAndRegeneration`
test exactly once in the one fresh, run-ID-bound `latest.log`. A zero-test,
stale, duplicate, or wrong-namespace run fails verification. The
dedicated-server smoke starts Forge with `--nogui`, accepts EULA only in the
ignored run directory used for the test, waits for server readiness, sends
`stop`, and checks that client-only classes are not loaded. Generated run
files, EULA files, worlds, logs, and crash reports are ignored and never
committed.

The smoke command verifies the supplied Java home is Eclipse Temurin
17.0.20+8 before it starts its child process group; relying on an ambient
default Java is rejected. It reports `NORMAL_STOP` or
`FORCED_PROCESS_GROUP_STOP` explicitly, and always verifies the process group
is gone independently of the log evidence.

The artifact gate checks both the unobfuscated and reobfuscated Jar-in-Jar
artifact, exact `META-INF/mods.toml`, singleton Jar-in-Jar metadata, one
`reality-foundation-api` and one `reality-core` nested entry, class major 61, unique
classes, `jdeps`, source policy, and license references. The runtime SBOM
contains actual resolved runtime artifacts and hashes. Its direct/provided
and bundled boundary is this module; the complete Forge/Minecraft transitive
distribution closure remains owned by the formal Forge/Minecraft distribution
SBOM and is recorded here as a hashed external-ownership closure.

## Evidence status

API unit tests and Forge 47.4.10 compilation are part of the repository gate.
The `run_server_smoke.py` command is specifically a ForgeGradle userdev
dedicated-server smoke: it uses the development classpath and proves
dedicated classloading/lifecycle wiring. It is not a packaged-artifact smoke;
installing the final `reality_foundation-...-all.jar` into a distribution
server, then joining/reconnecting, remains an operations integration/formal
gate pending. GameTestServer and a real client are separate runtime checks.
Until those commands are run in the fixed environment, their result must be
reported as pending. No client GUI screenshot is claimed or supplied by this
source tree; an unexecuted client run is not replaced by a mock image.

See `ARCHITECTURE.md`, `GUI_SPEC.md`, `COMPATIBILITY.md`, `SECURITY.md`, and
`CONTRIBUTING.md` for the contracts and verification details.
