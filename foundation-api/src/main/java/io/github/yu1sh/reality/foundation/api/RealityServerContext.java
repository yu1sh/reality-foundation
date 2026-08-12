package io.github.yu1sh.reality.foundation.api;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minecraft-independent state owned by exactly one adapter server context. */
public final class RealityServerContext implements AutoCloseable {
    public static final ServiceKey<FoundationHealthService> FOUNDATION_HEALTH =
            ServiceKey.of("foundation.health", FoundationHealthService.class);

    private final String contextId;
    private final ServiceRegistry services;
    private final FoundationDiagnosticsQuery diagnosticsQuery;
    private final DiagnosticsApplicationService diagnostics;
    private final AuditPort auditPort;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object closeMonitor = new Object();

    private RealityServerContext(String contextId, Clock clock, AuditPort auditPort) {
        this.contextId = requireContextId(contextId);
        this.auditPort = Objects.requireNonNull(auditPort, "auditPort");
        this.services = new ServiceRegistry();
        this.services.register(FOUNDATION_HEALTH, new FoundationHealthService(), "foundation");
        this.diagnosticsQuery = new FoundationDiagnosticsQuery(services, auditPort);
        this.diagnostics = new DiagnosticsApplicationService(
                diagnosticsQuery, auditPort, Objects.requireNonNull(clock, "clock"));
    }

    public static RealityServerContext create(Clock clock, AuditPort auditPort) {
        return new RealityServerContext("ctx-" + UUID.randomUUID(), clock, auditPort);
    }

    public String contextId() {
        return contextId;
    }

    public ServiceRegistry services() {
        ensureOpen();
        return services;
    }

    public FoundationDiagnosticsQuery diagnosticsQuery() {
        ensureOpen();
        return diagnosticsQuery;
    }

    public DiagnosticsApplicationService diagnostics() {
        ensureOpen();
        return diagnostics;
    }

    public AuditPort auditPort() {
        return auditPort;
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        synchronized (closeMonitor) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            RuntimeException failure = null;
            try {
                // Sessions stop accepting requests before service resources close.
                diagnostics.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
            try {
                services.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static String requireContextId(String contextId) {
        if (contextId == null || contextId.isBlank() || contextId.length() > 128
                || !contextId.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException("Context id is invalid");
        }
        return contextId;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Reality server context is closed");
        }
    }
}
