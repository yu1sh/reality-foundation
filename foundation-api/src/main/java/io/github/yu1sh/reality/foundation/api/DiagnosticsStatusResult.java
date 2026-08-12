package io.github.yu1sh.reality.foundation.api;

import java.util.Objects;
import java.util.Optional;

/** Stable result for the command path using the same diagnostics query. */
public final class DiagnosticsStatusResult {
    private final DiagnosticsSnapshot snapshot;
    private final FoundationError error;

    private DiagnosticsStatusResult(DiagnosticsSnapshot snapshot, FoundationError error) {
        this.snapshot = snapshot;
        this.error = error;
    }

    public static DiagnosticsStatusResult accepted(DiagnosticsSnapshot snapshot) {
        return new DiagnosticsStatusResult(Objects.requireNonNull(snapshot), null);
    }

    public static DiagnosticsStatusResult denied(FoundationError error) {
        return new DiagnosticsStatusResult(null, Objects.requireNonNull(error));
    }

    public boolean accepted() {
        return snapshot != null;
    }

    public DiagnosticsSnapshot snapshot() {
        if (!accepted()) {
            throw new IllegalStateException("Diagnostics status was denied");
        }
        return snapshot;
    }

    public Optional<FoundationError> error() {
        return Optional.ofNullable(error);
    }
}
