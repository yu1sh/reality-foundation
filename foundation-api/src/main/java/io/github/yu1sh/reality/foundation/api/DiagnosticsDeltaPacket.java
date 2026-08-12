package io.github.yu1sh.reality.foundation.api;

import java.util.Objects;

public record DiagnosticsDeltaPacket(DiagnosticsDelta delta) implements FoundationPacket {
    public DiagnosticsDeltaPacket {
        Objects.requireNonNull(delta, "delta");
    }

    @Override
    public int discriminator() {
        return 6;
    }
}
