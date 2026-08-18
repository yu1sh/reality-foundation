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
- `reality-core` migration source provenance `5e04ebc27c12d5b26b2a495e685d6ddf0bb21e22` (evidence only)
- canonical public remote `https://github.com/yu1sh/reality-core.git`, `main@bb858141e0cd628fce067093e8959255317ba16c`
- canonical workspace child `libs/reality-core@bb858141e0cd628fce067093e8959255317ba16c`
- network protocol `1`, API schema `foundation.api.v1`, release train `rt1-foundation`

## `reality-core` dependency contract

The `reality-core` reference in this repository has two deliberately separate
meanings. `reality_core_ref` and the `source_ref` in
`supply-chain/toolchain-manifest.json` identify the legacy source provenance
approved by the migration authority: `5e04ebc27c12d5b26b2a495e685d6ddf0bb21e22`.
That value is migration evidence only and is not the CI checkout ref.
`reality_core_child_baseline` identifies the canonical child repository and its
public remote, `https://github.com/yu1sh/reality-core.git`, at
`libs/reality-core` `main@bb858141e0cd628fce067093e8959255317ba16c`. CI checks out
and asserts that remote child commit. A source provenance is not a child
repository commit and these values must not be interchanged.

The previously recorded unapproved ref has no matching approved `MIGRATE` entry
in the new workspace and is not an accepted Foundation dependency ref.
The compatibility record, public version metadata, and source metadata continue
to preserve the approved source provenance above. The CI checkout assertion and
toolchain self-test use the canonical remote child commit. The canonical child
baseline must be verified against the child checkout before compile or smoke
validation.

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
7. When the server projection includes `recovery_command=available` (current
   authenticated permission level 4 and no streamer redaction), the admin tab
   presents the named recovery operation. The first click only arms a
   confirmation; the second sends the request and the server result is shown
   in the GUI.

The screen supports `en-US` and `ja-JP`, keyboard-focusable buttons, status
text in addition to color, and a compact layout that tolerates larger text.
No coordinate, JDBC detail, host, filesystem path, secret, or private player
field is included in a public projection. Streamer mode hides admin details.

## P-05/P-07/P-08 integration health

The Forge adapter receives one bounded `foundation_health_v1` Forge IMC report
from each member of the published P-05/P-07/P-08 deployment set. The report is
an internal server-to-server runtime boundary, not a client packet and not a
new command or Foundation-owned gameplay API.

| Service projection | Owner | Runtime report | Required boundary |
| --- | --- | --- | --- |
| `foundation.integration.reality.quests` | P-05 | consumer registration state plus P-05 status/admin contract `2` | P-07 reward scope/receive `v2`; P-08 consume `v2` and recovery `v1` |
| `foundation.integration.reality.economy` | P-07 | provider endpoint/version and lifecycle state | reward scope/receive `v2` |
| `foundation.integration.reality.inventory` | P-08 | provider endpoint/version and lifecycle state | consume `v2`; recovery `v1` |

Foundation registers these projections through the existing
`FoundationServiceContributor` and `HealthAwareService` seam. P-05 owns the
consumer-side registration result; P-07 and P-08 own their provider reports.
Foundation owns only aggregation and the redacted status projection. A
missing, malformed, duplicated, old-version, or initialization-failed report
becomes `UNAVAILABLE` or `DEGRADED` for that projection and does not prevent
the other projections or the Foundation context from starting.

The report supplier is evaluated against the active `MinecraftServer` when the
server-produced health snapshot is queried, so normal `ServerStarted` recovery
and failure state are reflected without caching player, inventory, reward,
FROZEN, operation-key, exception, Git SHA, or repository data. Integration
health is admin-only: non-admin and streamer-mode projections receive neither
these service entries nor their detail. `/realityfoundation status` and the
existing System Status GUI receive the same administrator `serviceHealth`
projection, including the same session, revision, permission, and replay
validation. Server stop closes the context before unbinding the active health
server; no child endpoint is closed or mutated by Foundation.

## Server commands

- `/realityfoundation status` uses the same `FoundationDiagnosticsQuery` as
  the GUI and displays only the caller's server-authorized projection.
- `/realityfoundation recovery clear-sessions` is a permission-level-4
  recovery operation. The command adapter and the GUI recovery packet both
  use the same application service and complete
  `FoundationMutationEnvelope` (`requestId`, `operationId`, `sessionId`, and
  exact `expectedVersion`) shape. The GUI packet contains no actor or
  permission authority; the active server binds the authenticated actor and
  rechecks the active session, lifecycle, revision, permission projection,
  and clock before commit. Same operation ID plus the same fingerprint replays
  the recorded result without clearing a newer session; a different
  fingerprint returns `OPERATION_CONFLICT`. The bounded in-memory replay
  ledger is deliberately not durable across a server restart.

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

The canonical new-workspace `reality-core` checkout at
`../../libs/reality-core` relative to this repository, matching the public
remote at the recorded child commit, is required by default; an isolated
reviewed checkout can be selected with `-PrealityCoreDir=...`. Verify the
selected child checkout against the recorded baseline before compile or smoke
validation. The custom launcher accepts only Gradle 8.8 and verifies the
recorded distribution SHA when it downloads the distribution.

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
