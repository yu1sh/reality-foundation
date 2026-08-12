package io.github.yu1sh.reality.foundation.api;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Development-only audit boundary. It counts attempted events for tests but
 * never claims that an event was persisted. A service using this port may
 * serve read-only diagnostics, but management mutation is fail-closed because
 * the returned {@link AuditDisposition#NOT_CONFIGURED} is not authorization.
 */
public final class NoopAuditPort implements AuditPort {
    private final AtomicInteger attemptedEvents = new AtomicInteger();

    @Override
    public AuditDisposition record(DiagnosticAuditEvent event) {
        if (event == null) {
            return AuditDisposition.REJECTED;
        }
        attemptedEvents.incrementAndGet();
        return AuditDisposition.NOT_CONFIGURED;
    }

    public int attemptedEvents() {
        return attemptedEvents.get();
    }
}
