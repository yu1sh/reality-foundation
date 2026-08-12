package io.github.yu1sh.reality.foundation.api;

/** Stable application errors for the read-only diagnostics boundary. */
public enum FoundationErrorCode {
    MALFORMED_REQUEST("malformed_request", "foundation.error.malformed_request"),
    HANDSHAKE_REQUIRED("handshake_required", "foundation.error.handshake_required"),
    PERMISSION_DENIED("permission_denied", "foundation.error.permission_denied"),
    INVALID_SESSION("invalid_session", "foundation.error.invalid_session"),
    SESSION_EXPIRED("session_expired", "foundation.error.session_expired"),
    REVISION_CONFLICT("revision_conflict", "foundation.error.revision_conflict"),
    OPERATION_CONFLICT("operation_conflict", "foundation.error.operation_conflict"),
    RATE_LIMITED("rate_limited", "foundation.error.rate_limited"),
    RESOURCE_LIMITED("resource_limited", "foundation.error.resource_limited"),
    INTERNAL_FAILURE("internal_failure", "foundation.error.internal_failure");

    private final String code;
    private final String messageKey;

    FoundationErrorCode(String code, String messageKey) {
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
