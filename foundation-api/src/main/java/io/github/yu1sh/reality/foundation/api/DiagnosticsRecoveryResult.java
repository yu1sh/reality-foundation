package io.github.yu1sh.reality.foundation.api;

import java.util.Objects;
import java.util.Optional;

/** Stable success/denial/failure result for the management recovery command. */
public final class DiagnosticsRecoveryResult {
    private final boolean accepted;
    private final FoundationError error;
    private final AuditDisposition auditDisposition;

    private DiagnosticsRecoveryResult(
            boolean accepted, FoundationError error, AuditDisposition auditDisposition) {
        this.accepted = accepted;
        this.error = error;
        this.auditDisposition = Objects.requireNonNull(auditDisposition, "auditDisposition");
    }

    public static DiagnosticsRecoveryResult accepted(AuditDisposition auditDisposition) {
        return new DiagnosticsRecoveryResult(true, null, auditDisposition);
    }

    public static DiagnosticsRecoveryResult denied(
            FoundationError error, AuditDisposition auditDisposition) {
        return new DiagnosticsRecoveryResult(
                false, Objects.requireNonNull(error, "error"), auditDisposition);
    }

    public boolean accepted() {
        return accepted;
    }

    public Optional<FoundationError> error() {
        return Optional.ofNullable(error);
    }

    public AuditDisposition auditDisposition() {
        return auditDisposition;
    }
}
