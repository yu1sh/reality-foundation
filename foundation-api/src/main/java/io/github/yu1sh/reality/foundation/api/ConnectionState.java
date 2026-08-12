package io.github.yu1sh.reality.foundation.api;

/** Server-observed connection state used by the diagnostics projection. */
public enum ConnectionState {
    NOT_NEGOTIATED,
    HANDSHAKE_ACCEPTED,
    REJECTED
}
