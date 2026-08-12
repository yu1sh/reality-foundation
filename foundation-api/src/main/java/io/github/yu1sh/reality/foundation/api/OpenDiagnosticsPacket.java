package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.identity.RequestId;

import java.util.Objects;

public record OpenDiagnosticsPacket(RequestId requestId) implements FoundationPacket {
    public OpenDiagnosticsPacket {
        Objects.requireNonNull(requestId, "requestId");
    }

    @Override
    public int discriminator() {
        return 2;
    }
}
