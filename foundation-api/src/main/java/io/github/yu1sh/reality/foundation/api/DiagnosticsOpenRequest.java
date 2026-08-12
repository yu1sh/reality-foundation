package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.identity.RequestId;

import java.util.Objects;

/** Client request for a new read-only diagnostics session. */
public record DiagnosticsOpenRequest(RequestId requestId) {
    public DiagnosticsOpenRequest {
        Objects.requireNonNull(requestId, "requestId");
    }
}
