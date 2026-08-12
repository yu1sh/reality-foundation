package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.identity.RequestId;

import java.util.Objects;

public record DiagnosticsErrorPacket(RequestId requestId, FoundationError error) implements FoundationPacket {
    public DiagnosticsErrorPacket {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(error, "error");
    }

    @Override
    public int discriminator() {
        return 7;
    }
}
