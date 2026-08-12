package io.github.yu1sh.reality.foundation.api;

/**
 * Result of a server-owned lifecycle invalidation. This boundary is not a
 * client packet: callers must supply an actor and session identity observed by
 * the server. An invalidation is idempotent and never creates a new session.
 */
public final class DiagnosticsSessionInvalidationResult {
    public enum Kind {
        INVALIDATED,
        CANCELLED,
        NOT_FOUND,
        ACTOR_MISMATCH,
        CLOSED,
        MALFORMED
    }

    private final Kind kind;

    private DiagnosticsSessionInvalidationResult(Kind kind) {
        this.kind = kind;
    }

    static DiagnosticsSessionInvalidationResult of(Kind kind) {
        return new DiagnosticsSessionInvalidationResult(kind);
    }

    public Kind kind() {
        return kind;
    }

    public boolean invalidated() {
        return kind == Kind.INVALIDATED || kind == Kind.CANCELLED;
    }

    /** True when a callback reservation was cancelled without an active session. */
    public boolean cancelled() {
        return kind == Kind.CANCELLED;
    }
}
