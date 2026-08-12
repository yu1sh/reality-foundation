package io.github.yu1sh.reality.foundation.api;

import java.util.Objects;

public record ClientHelloPacket(FoundationHandshake handshake) implements FoundationPacket {
    public ClientHelloPacket {
        Objects.requireNonNull(handshake, "handshake");
    }

    @Override
    public int discriminator() {
        return 1;
    }
}
