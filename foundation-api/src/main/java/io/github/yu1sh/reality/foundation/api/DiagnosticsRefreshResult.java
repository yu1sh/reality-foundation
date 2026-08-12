package io.github.yu1sh.reality.foundation.api;

import java.util.Objects;
import java.util.Optional;

/** Snapshot/delta/unchanged result for a server-validated refresh. */
public final class DiagnosticsRefreshResult {
    public enum Kind {
        SNAPSHOT,
        DELTA,
        UNCHANGED,
        DENIED
    }

    private final Kind kind;
    private final DiagnosticsSnapshot snapshot;
    private final DiagnosticsDelta delta;
    private final FoundationError error;

    private DiagnosticsRefreshResult(
            Kind kind, DiagnosticsSnapshot snapshot, DiagnosticsDelta delta, FoundationError error) {
        this.kind = kind;
        this.snapshot = snapshot;
        this.delta = delta;
        this.error = error;
    }

    public static DiagnosticsRefreshResult snapshot(DiagnosticsSnapshot snapshot) {
        return new DiagnosticsRefreshResult(Kind.SNAPSHOT, Objects.requireNonNull(snapshot), null, null);
    }

    public static DiagnosticsRefreshResult delta(DiagnosticsDelta delta) {
        return new DiagnosticsRefreshResult(Kind.DELTA, null, Objects.requireNonNull(delta), null);
    }

    public static DiagnosticsRefreshResult unchanged() {
        return new DiagnosticsRefreshResult(Kind.UNCHANGED, null, null, null);
    }

    public static DiagnosticsRefreshResult denied(FoundationError error) {
        return new DiagnosticsRefreshResult(Kind.DENIED, null, null, Objects.requireNonNull(error));
    }

    public Kind kind() {
        return kind;
    }

    public Optional<DiagnosticsSnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public Optional<DiagnosticsDelta> delta() {
        return Optional.ofNullable(delta);
    }

    public Optional<FoundationError> error() {
        return Optional.ofNullable(error);
    }
}
