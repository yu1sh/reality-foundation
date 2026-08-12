package io.github.yu1sh.reality.foundation.api;

import java.util.Objects;

public record DiagnosticsSnapshotPacket(DiagnosticsSnapshot snapshot) implements FoundationPacket {
    public DiagnosticsSnapshotPacket {
        Objects.requireNonNull(snapshot, "snapshot");
    }

    @Override
    public int discriminator() {
        return 5;
    }
}
