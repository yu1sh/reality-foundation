package io.github.yu1sh.reality.foundation.api;

import java.util.Objects;
import java.util.Optional;

/** Stable result for an admin/query session-count read during lifecycle races. */
public final class DiagnosticsCountResult {
    private final Integer count;
    private final FoundationError error;

    private DiagnosticsCountResult(Integer count, FoundationError error) {
        this.count = count;
        this.error = error;
    }

    public static DiagnosticsCountResult accepted(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Session count cannot be negative");
        }
        return new DiagnosticsCountResult(count, null);
    }

    public static DiagnosticsCountResult denied(FoundationError error) {
        return new DiagnosticsCountResult(null, Objects.requireNonNull(error, "error"));
    }

    public boolean accepted() {
        return count != null;
    }

    public int count() {
        if (!accepted()) {
            throw new IllegalStateException("Session count was denied");
        }
        return count;
    }

    public Optional<FoundationError> error() {
        return Optional.ofNullable(error);
    }
}
