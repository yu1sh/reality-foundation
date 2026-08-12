package io.github.yu1sh.reality.foundation.api;

/** Stable reason codes; values are safe for client-facing disconnect text. */
public enum HandshakeRejectReason {
    PROTOCOL_MISMATCH("protocol_mismatch", "foundation.error.protocol_mismatch"),
    API_SCHEMA_MISMATCH("api_schema_mismatch", "foundation.error.api_schema_mismatch"),
    MOD_VERSION_MISMATCH("mod_version_mismatch", "foundation.error.mod_version_mismatch"),
    RELEASE_TRAIN_MISMATCH("release_train_mismatch", "foundation.error.release_train_mismatch"),
    MALFORMED_HANDSHAKE("malformed_handshake", "foundation.error.malformed_handshake");

    private final String code;
    private final String messageKey;

    HandshakeRejectReason(String code, String messageKey) {
        this.code = code;
        this.messageKey = messageKey;
    }

    public String code() {
        return code;
    }

    public String messageKey() {
        return messageKey;
    }
}
