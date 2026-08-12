package io.github.yu1sh.reality.foundation.api;

import java.util.Objects;
import java.util.Optional;

/** Application result for an authenticated diagnostics open request. */
public final class DiagnosticsOpenResult {
    private final DiagnosticsSnapshot snapshot;
    private final FoundationError error;

    private DiagnosticsOpenResult(DiagnosticsSnapshot snapshot, FoundationError error) {
        this.snapshot = snapshot;
        this.error = error;
    }

    public static DiagnosticsOpenResult accepted(DiagnosticsSnapshot snapshot) {
        return new DiagnosticsOpenResult(Objects.requireNonNull(snapshot, "snapshot"), null);
    }

    public static DiagnosticsOpenResult denied(FoundationError error) {
        return new DiagnosticsOpenResult(null, Objects.requireNonNull(error, "error"));
    }

    public boolean accepted() {
        return snapshot != null;
    }

    public DiagnosticsSnapshot snapshot() {
        if (!accepted()) {
            throw new IllegalStateException("Diagnostics open was denied");
        }
        return snapshot;
    }

    public Optional<FoundationError> error() {
        return Optional.ofNullable(error);
    }
}
