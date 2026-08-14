package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.identity.RequestId;

import java.util.Objects;

/** Server result for one diagnostics recovery request attempt. */
public record DiagnosticsRecoveryResultPacket(
        RequestId requestId,
        DiagnosticsRecoveryResult result) implements FoundationPacket {
    public DiagnosticsRecoveryResultPacket {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(result, "result");
    }

    @Override
    public int discriminator() {
        return 9;
    }
}
