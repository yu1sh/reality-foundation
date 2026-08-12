# Security policy

## Boundary

The server is authoritative for handshake, permissions, actor identity,
session lifetime, rate limits, revisions, service health projection, menu
creation, and recovery commands. The client cannot choose an admin projection,
session owner, connection state, or operation result.

## Input and output controls

Packet discriminators, byte lengths, string lengths, map counts, service
counts, UTF-8, enum values, revision direction, and trailing bytes are
validated. Logs, packet text, errors, and `toString` methods do not expose
secrets, paths, hosts, request bodies, or private player data. Query, health,
and audit callbacks run outside state monitors and are normalized to stable
errors on failure.

## Reporting

Do not file a public issue with credentials, private server data, world data,
full request payloads, or undisclosed exploit details. Use the repository
maintainer's private security channel when one is configured; otherwise open a
minimal issue asking for a private contact route. Include the fixed mod,
Forge, Java, and protocol versions without attaching secrets.

The audit integration is not implemented here. `NOT_CONFIGURED` is explicit
development status, not proof that an operation was recorded.
