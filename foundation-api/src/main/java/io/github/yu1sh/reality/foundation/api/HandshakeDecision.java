package io.github.yu1sh.reality.foundation.api;

import java.util.Objects;
import java.util.Optional;

/** Result of exact handshake comparison. */
public final class HandshakeDecision {
    private final boolean accepted;
    private final HandshakeRejectReason rejection;

    private HandshakeDecision(boolean accepted, HandshakeRejectReason rejection) {
        this.accepted = accepted;
        this.rejection = rejection;
    }

    public static HandshakeDecision acceptedDecision() {
        return new HandshakeDecision(true, null);
    }

    public static HandshakeDecision rejected(HandshakeRejectReason reason) {
        return new HandshakeDecision(false, Objects.requireNonNull(reason, "reason"));
    }

    public boolean accepted() {
        return accepted;
    }

    public Optional<HandshakeRejectReason> rejection() {
        return Optional.ofNullable(rejection);
    }

    public String stableReason() {
        return accepted ? "accepted" : rejection.code();
    }
}
