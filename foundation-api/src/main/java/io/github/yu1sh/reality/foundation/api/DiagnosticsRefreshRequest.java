package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.identity.RequestId;
import io.github.yu1sh.reality.identity.SessionId;
import io.github.yu1sh.reality.version.Revision;

import java.util.Objects;

/** Read-only refresh envelope; deliberately has no OperationId. */
public record DiagnosticsRefreshRequest(
        RequestId requestId,
        SessionId sessionId,
        Revision expectedRevision) {
    public DiagnosticsRefreshRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(expectedRevision, "expectedRevision");
    }
}
