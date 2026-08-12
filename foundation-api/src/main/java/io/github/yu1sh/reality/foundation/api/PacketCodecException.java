package io.github.yu1sh.reality.foundation.api;

/** Stable malformed/oversize/unknown packet failure without payload echoing. */
public final class PacketCodecException extends IllegalArgumentException {
    private final String reason;

    public PacketCodecException(String reason) {
        super(reason);
        if (reason == null || !reason.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Packet codec reason is invalid");
        }
        this.reason = reason;
    }

    public PacketCodecException(String reason, Throwable cause) {
        super(reason, cause);
        if (reason == null || !reason.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Packet codec reason is invalid");
        }
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
