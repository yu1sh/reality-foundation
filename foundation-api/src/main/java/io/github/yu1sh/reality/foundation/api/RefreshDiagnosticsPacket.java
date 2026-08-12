package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.identity.RequestId;
import io.github.yu1sh.reality.identity.SessionId;
import io.github.yu1sh.reality.version.Revision;

import java.util.Objects;

public record RefreshDiagnosticsPacket(
        RequestId requestId,
        SessionId sessionId,
        Revision expectedRevision) implements FoundationPacket {
    public RefreshDiagnosticsPacket {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(expectedRevision, "expectedRevision");
    }

    @Override
    public int discriminator() {
        return 3;
    }
}
