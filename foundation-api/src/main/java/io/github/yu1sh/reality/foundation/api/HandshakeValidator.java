package io.github.yu1sh.reality.foundation.api;

import java.util.Objects;

/** Compares every compatibility coordinate; no ranges are accepted. */
public final class HandshakeValidator {
    private final FoundationHandshake expected;

    public HandshakeValidator(FoundationHandshake expected) {
        this.expected = Objects.requireNonNull(expected, "expected");
    }

    public HandshakeDecision evaluate(FoundationHandshake actual) {
        if (actual == null) {
            return HandshakeDecision.rejected(HandshakeRejectReason.MALFORMED_HANDSHAKE);
        }
        if (actual.networkProtocol() != expected.networkProtocol()) {
            return HandshakeDecision.rejected(HandshakeRejectReason.PROTOCOL_MISMATCH);
        }
        if (!actual.apiSchema().equals(expected.apiSchema())) {
            return HandshakeDecision.rejected(HandshakeRejectReason.API_SCHEMA_MISMATCH);
        }
        if (!actual.modVersion().equals(expected.modVersion())) {
            return HandshakeDecision.rejected(HandshakeRejectReason.MOD_VERSION_MISMATCH);
        }
        if (!actual.releaseTrain().equals(expected.releaseTrain())) {
            return HandshakeDecision.rejected(HandshakeRejectReason.RELEASE_TRAIN_MISMATCH);
        }
        return HandshakeDecision.acceptedDecision();
    }

    public FoundationHandshake expected() {
        return expected;
    }
}
