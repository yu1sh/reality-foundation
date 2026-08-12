package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.identity.ActorId;
import io.github.yu1sh.reality.identity.OperationId;
import io.github.yu1sh.reality.identity.RequestId;

import java.time.Instant;
import java.util.Objects;

/** Correlation-only audit event; no request payload or private data is stored. */
public record DiagnosticAuditEvent(
        RequestId requestId,
        OperationId operationId,
        ActorId actor,
        String action,
        Instant occurredAt) {
    public DiagnosticAuditEvent {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(actor, "actor");
        if (action == null || !action.matches("[a-z][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("Audit action is invalid");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    @Override
    public String toString() {
        return "DiagnosticAuditEvent[action=" + action + ", hasOperationId="
                + (operationId != null) + "]";
    }
}
