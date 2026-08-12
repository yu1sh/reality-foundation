# Native System Status GUI specification

## Entry and state

- F8 is the default client keybind after an exact server handshake.
- A future world/menu導線 may call the same open packet; it does not bypass
  server validation.
- The client sends a `RequestId`. The server creates the `SessionId` and
  initial `Revision`; no client value is authoritative.
- The server sends one initial snapshot, then only forward revision deltas.
  Full snapshots are not sent every tick.

## General projection

The general tab may show only protocol, API schema, mod/release train,
connection state, service count, audit integration state, and public health
status/message keys. Health order is deterministic registry registration order.
Values are bounded and are never coordinates, hostnames, paths, JDBC data,
secrets, private player data, implementation classes, or owner internals.
The native screen has separate overview and service-health tabs. Health is
shown as service id plus translated status and message, seven rows per page;
all 64 bounded entries are reachable with keyboard-focusable previous/next
buttons or mouse-wheel paging. Long localized labels use measured-font
ellipsis and retain the complete value in a bounded per-row hit-test
collection for a hover tooltip. When one or more rows are shortened, a
keyboard-focusable Details control switches to an exclusive detail view.
Previous detail and Next detail paginate the wrapped full value and advance
between every shortened row; Back returns to the list. The detail content
pane remains y=28..150 and uses ten lines per page, so keyboard users do not
depend on a mouse hover and the 228px screen remains within a 240px scaled
height.

## Admin projection

Permission level 2 or higher is required and is rechecked on every open and
refresh. Streamer mode suppresses admin values even for an otherwise eligible
actor. On permission downgrade or streamer-mode enablement, every previously
visible admin key appears in `removedAdminKeys`; client application clears the
admin map. A permission upgrade adds values only after a new server snapshot.

## Controls and errors

Refresh is read-only and carries request ID, session ID, and expected revision;
it intentionally carries no operation ID. Close removes the native menu and
invalidates only its server-bound actor/session pair. The admin tab is hidden
when the server projection is not admin-allowed. Buttons are keyboard
focusable, status is expressed as text as well as color, and translations are
required in `en-US` and `ja-JP`. Known field/value labels are translated,
including the admin values. A permission downgrade or streamer transition
immediately hides and deselects the admin tab.

Stable error keys include malformed request, handshake required, permission
denied, invalid/expired session, revision conflict, rate limited, resource
limited, operation conflict, internal failure, and the five handshake
rejection reasons. A replay or out-of-order delta is rejected before any
client map is changed.

## Recovery mutation envelope

`FoundationMutationEnvelope` defines request ID, operation ID, session ID, and
expected version for the server-side recovery mutation. The current client
screen remains read-only and registers no fabricated mutation packet; the
permission-level-4 command and any future GUI action use the same Foundation
application service. That service requires a server-issued active session and
exact revision, uses only server-authenticated actor/permission projection,
fails closed unless its audit result is `RECORDED`, and replays a matching
operation ID without another mutation.
