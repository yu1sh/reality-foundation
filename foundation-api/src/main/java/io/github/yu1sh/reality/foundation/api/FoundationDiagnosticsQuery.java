package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.identity.SessionId;
import io.github.yu1sh.reality.version.Revision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one application query used by both the GUI handlers and the command.
 * Values are intentionally limited to public status and non-sensitive admin
 * recovery metadata; no host, path, JDBC, secret, or private player field is
 * ever added here.
 */
public final class FoundationDiagnosticsQuery {
    private final ServiceRegistry registry;
    private final AuditPort auditPort;
    private final AtomicLong revision = new AtomicLong(1L);

    public FoundationDiagnosticsQuery(ServiceRegistry registry) {
        this(registry, new NoopAuditPort());
    }

    public FoundationDiagnosticsQuery(ServiceRegistry registry, AuditPort auditPort) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.auditPort = Objects.requireNonNull(auditPort, "auditPort");
    }

    public Revision revision() {
        return Revision.of(revision.get());
    }

    /** Called only when a server-side foundation status source changes. */
    public void markChanged() {
        revision.updateAndGet(value -> Math.incrementExact(value));
    }

    public DiagnosticsSnapshot snapshot(
            SessionId sessionId, AuthenticatedActor actor, ConnectionState connectionState) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(connectionState, "connectionState");

        List<ServiceHealth> health = registry.healthSnapshot();
        Map<String, String> publicValues = new LinkedHashMap<>();
        publicValues.put("foundation.connection", connectionState.name().toLowerCase());
        publicValues.put("foundation.protocol", Integer.toString(FoundationVersion.NETWORK_PROTOCOL));
        publicValues.put("foundation.api_schema", FoundationVersion.API_SCHEMA);
        publicValues.put("foundation.mod_version", FoundationVersion.MOD_VERSION);
        publicValues.put("foundation.release_train", FoundationVersion.RELEASE_TRAIN);
        publicValues.put("foundation.service_count", Integer.toString(health.size()));
        publicValues.put("foundation.audit", auditProjection());

        Map<String, String> adminValues = new LinkedHashMap<>();
        if (actor.mayViewAdminDiagnostics()) {
            adminValues.put("context_state", "active");
            adminValues.put("registry_close_order", "reverse_registration");
            adminValues.put("session_validation", "actor_expiry_rate_revision");
            // Presence of this server-issued projection key is the GUI's
            // recovery eligibility signal. Permission level 2 may inspect
            // diagnostics, but only level 4 may be offered the mutation.
            if (actor.permissionLevel() >= 4) {
                adminValues.put("recovery_command", "available");
            }
        }
        return DiagnosticsSnapshot.of(
                sessionId,
                revision(),
                actor.locale(),
                actor.streamerMode(),
                actor.mayViewAdminDiagnostics(),
                connectionState,
                publicValues,
                adminValues,
                health);
    }

    private String auditProjection() {
        try {
            AuditAvailability disposition = auditPort.availability();
            if (disposition == null) {
                return "unavailable";
            }
            return switch (disposition) {
                case CONFIGURED -> "configured";
                case UNAVAILABLE -> "unavailable";
                case NOT_CONFIGURED -> "not_configured";
            };
        } catch (RuntimeException failure) {
            return "unavailable";
        }
    }
}
