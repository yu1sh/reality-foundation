package io.github.yu1sh.reality.foundation.api;

/**
 * Adapter boundary for a future append-only audit repository.
 *
 * <p>{@link #availability()} is a non-mutating, server-owned projection
 * hint. It must never be interpreted as proof that a particular event was
 * persisted; management mutation still requires {@link AuditDisposition#RECORDED}
 * from {@link #record(DiagnosticAuditEvent)}.
 */
public interface AuditPort {
    AuditDisposition record(DiagnosticAuditEvent event);

    /**
     * Returns the configured projection state without recording an event.
     * Implementations that are not installed must retain the conservative
     * {@link AuditDisposition#NOT_CONFIGURED} default.
     */
    default AuditAvailability availability() {
        return AuditAvailability.NOT_CONFIGURED;
    }
}
