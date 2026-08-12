# Architecture

## Modules and ownership

```text
reality-foundation-api
  Minecraft-independent service/context, handshake, diagnostics projection,
  packet codec, session application boundary, and future mutation envelope

forge-1.20.1
  Forge event/command/network/menu/screen adapter only
```

`reality-foundation-api` has a source and bytecode boundary check for
`net.minecraft` and `net.minecraftforge`. Only the Forge adapter imports those
types. `reality-core` is consumed at the fixed reviewed ref and is not copied
or modified here.

## Server state

`RealityServerContextManager` uses identity semantics for `MinecraftServer`.
The API `RealityServerContext` contains a thread-safe `ServiceRegistry`, the
diagnostics query, diagnostics application service, and a development-only
Noop audit port. There is no static feature state. Registry logical IDs are
unique across all Java types; a request for the same ID under another type is
an explicit type-mismatch error. Registration order is retained for health
and descriptor snapshots; close order is the reverse registration order.

Registry health callbacks execute against an immutable registration snapshot
outside the registry monitor. The snapshot-at-call-start entries are evaluated
even if unregister or close races with callback execution. Exceptions and null
health values become the stable unavailable projection.

## Request sequence

```text
client hello
    -> server exact validator
    -> accepted connection state
client open(requestId)
    -> authenticated ServerPlayer + permission + actor
    -> query/audit callbacks outside application monitor
    -> sessionId + revision + initial snapshot
client refresh(sessionId, expectedRevision)
    -> session/actor/expiry/rate/protocol/revision validation
    -> query projection comparison outside monitor
    -> forward delta or unchanged
```

The application uses a reserve/callback/revalidate/commit boundary. There is
one active session per actor, a 64-session context cap, bounded service and
packet projections, and a 250 ms default refresh rate. Logout, exact server
menu removal, recovery clear, and context close cancel reservations before a
callback can commit.

## Packaging

The Forge artifact contains the Forge-owned classes at the root and exactly
one Jar-in-Jar `reality-foundation-api` and one `reality-core` entry. Forge metadata
uses a singleton exact version pin. The artifact validator recursively checks
all root/nested classes for major 61 and rejects duplicate class paths.

Forge and Minecraft are provided runtime dependencies. The SBOM records the
actual resolved closure hashes and distinguishes this repository's bundled
components from the formal distribution's transitive ownership.
