package io.github.yu1sh.reality.foundation.api;

import java.util.Objects;

public record HandshakeResultPacket(
        boolean accepted,
        String reason) implements FoundationPacket {
    public HandshakeResultPacket {
        Objects.requireNonNull(reason, "reason");
        if (reason.length() > 64 || !reason.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Handshake result reason is invalid");
        }
    }

    @Override
    public int discriminator() {
        return 4;
    }
}
