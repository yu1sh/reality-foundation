package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.gui.GuiSnapshot;
import io.github.yu1sh.reality.gui.LocaleTag;
import io.github.yu1sh.reality.identity.SessionId;
import io.github.yu1sh.reality.version.Revision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Server-issued, redacted read-only diagnostics state. */
public final class DiagnosticsSnapshot {
    public static final int MAX_FIELDS = 128;
    public static final int MAX_SERVICES = 64;
    public static final int MAX_VALUE_LENGTH = 512;

    private final SessionId sessionId;
    private final Revision revision;
    private final LocaleTag locale;
    private final boolean streamerMode;
    private final boolean adminAllowed;
    private final ConnectionState connectionState;
    private final Map<String, String> publicValues;
    private final Map<String, String> adminValues;
    private final List<ServiceHealth> serviceHealth;

    private DiagnosticsSnapshot(
            SessionId sessionId,
            Revision revision,
            LocaleTag locale,
            boolean streamerMode,
            boolean adminAllowed,
            ConnectionState connectionState,
            Map<String, String> publicValues,
            Map<String, String> adminValues,
            List<ServiceHealth> serviceHealth) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.revision = Objects.requireNonNull(revision, "revision");
        this.locale = Objects.requireNonNull(locale, "locale");
        this.connectionState = Objects.requireNonNull(connectionState, "connectionState");
        this.streamerMode = streamerMode;
        this.adminAllowed = adminAllowed && !streamerMode;
        this.publicValues = copyValues(publicValues);
        this.adminValues = this.adminAllowed ? copyValues(adminValues) : Map.of();
        if (this.publicValues.size() + this.adminValues.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("Diagnostics snapshot has too many fields");
        }
        this.serviceHealth = copyHealth(serviceHealth);
        if (this.serviceHealth.size() > MAX_SERVICES) {
            throw new IllegalArgumentException("Diagnostics snapshot has too many services");
        }
    }

    public static DiagnosticsSnapshot of(
            SessionId sessionId,
            Revision revision,
            LocaleTag locale,
            boolean streamerMode,
            boolean adminAllowed,
            ConnectionState connectionState,
            Map<String, String> publicValues,
            Map<String, String> adminValues,
            List<ServiceHealth> serviceHealth) {
        return new DiagnosticsSnapshot(
                sessionId, revision, locale, streamerMode, adminAllowed, connectionState,
                publicValues, adminValues, serviceHealth);
    }

    public SessionId sessionId() {
        return sessionId;
    }

    public Revision revision() {
        return revision;
    }

    public LocaleTag locale() {
        return locale;
    }

    public boolean streamerMode() {
        return streamerMode;
    }

    public boolean adminAllowed() {
        return adminAllowed;
    }

    public ConnectionState connectionState() {
        return connectionState;
    }

    public Map<String, String> publicValues() {
        return publicValues;
    }

    public Map<String, String> adminValues() {
        return adminValues;
    }

    public List<ServiceHealth> serviceHealth() {
        return serviceHealth;
    }

    /** Core GUI contract view; admin values are namespaced and server-redacted. */
    public GuiSnapshot guiSnapshot() {
        return GuiSnapshot.of(sessionId, revision, locale, streamerMode, guiValues());
    }

    /** Returns the same server projection under a newly allocated revision. */
    public DiagnosticsSnapshot withRevision(Revision newRevision) {
        return of(sessionId, newRevision, locale, streamerMode, adminAllowed,
                connectionState, publicValues, adminValues, serviceHealth);
    }

    /**
     * Applies only the next server delta. A replayed, skipped, or out-of-order
     * delta is rejected before any client state is changed.
     */
    public DiagnosticsSnapshot apply(DiagnosticsDelta delta) {
        Objects.requireNonNull(delta, "delta");
        if (!sessionId.equals(delta.sessionId()) || !revision.equals(delta.fromRevision())) {
            throw new IllegalArgumentException("delta_base_mismatch");
        }
        Map<String, String> nextPublic = new LinkedHashMap<>(publicValues);
        delta.removedPublicKeys().forEach(nextPublic::remove);
        nextPublic.putAll(delta.updatedPublicValues());
        Map<String, String> nextAdmin = new LinkedHashMap<>(adminValues);
        delta.removedAdminKeys().forEach(nextAdmin::remove);
        nextAdmin.putAll(delta.updatedAdminValues());
        if (!delta.adminAllowed()) {
            nextAdmin.clear();
        }
        return of(sessionId, delta.toRevision(), delta.locale(), delta.streamerMode(),
                delta.adminAllowed(), delta.connectionState(), nextPublic, nextAdmin,
                delta.applyServiceHealth(serviceHealth));
    }

    Map<String, String> guiValues() {
        Map<String, String> result = new LinkedHashMap<>(publicValues);
        adminValues.forEach((key, value) -> result.put("admin." + key, value));
        return Map.copyOf(result);
    }

    private static Map<String, String> copyValues(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        if (values.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("Diagnostics snapshot has too many fields");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}")
                    || value == null || value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("Diagnostics field is invalid");
            }
            copy.put(key, value);
        });
        return Map.copyOf(copy);
    }

    private static List<ServiceHealth> copyHealth(List<ServiceHealth> health) {
        Objects.requireNonNull(health, "health");
        if (health.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Diagnostics health cannot contain null");
        }
        List<ServiceHealth> copy = new ArrayList<>(health);
        if (copy.stream().map(ServiceHealth::serviceId).distinct().count() != copy.size()) {
            throw new IllegalArgumentException("Diagnostics health contains duplicate service id");
        }
        return List.copyOf(copy);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DiagnosticsSnapshot that
                && sessionId.equals(that.sessionId)
                && revision.equals(that.revision)
                && locale.equals(that.locale)
                && streamerMode == that.streamerMode
                && adminAllowed == that.adminAllowed
                && connectionState == that.connectionState
                && publicValues.equals(that.publicValues)
                && adminValues.equals(that.adminValues)
                && serviceHealth.equals(that.serviceHealth);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, revision, locale, streamerMode, adminAllowed,
                connectionState, publicValues, adminValues, serviceHealth);
    }

    @Override
    public String toString() {
        return "DiagnosticsSnapshot[revision=" + revision + ", locale=" + locale
                + ", streamerMode=" + streamerMode + ", adminAllowed=" + adminAllowed
                + ", connectionState=" + connectionState + ", fieldCount="
                + (publicValues.size() + adminValues.size()) + ", serviceCount="
                + serviceHealth.size() + "]";
    }
}
