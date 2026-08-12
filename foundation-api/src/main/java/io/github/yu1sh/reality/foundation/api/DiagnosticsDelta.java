package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.gui.GuiDelta;
import io.github.yu1sh.reality.gui.LocaleTag;
import io.github.yu1sh.reality.identity.SessionId;
import io.github.yu1sh.reality.version.Revision;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Forward-only, bounded state change from one server revision to another. */
public final class DiagnosticsDelta {
    private static final Pattern SERVICE_ID = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

    private final SessionId sessionId;
    private final Revision fromRevision;
    private final Revision toRevision;
    private final LocaleTag locale;
    private final boolean streamerMode;
    private final boolean adminAllowed;
    private final ConnectionState connectionState;
    private final Map<String, String> updatedPublicValues;
    private final Map<String, String> updatedAdminValues;
    private final Set<String> removedPublicKeys;
    private final Set<String> removedAdminKeys;
    private final List<ServiceHealth> updatedServiceHealth;
    private final Set<String> removedServiceHealthIds;
    /** Empty when registration order is unchanged; otherwise the new order. */
    private final List<String> serviceHealthOrder;

    private DiagnosticsDelta(
            SessionId sessionId,
            Revision fromRevision,
            Revision toRevision,
            LocaleTag locale,
            boolean streamerMode,
            boolean adminAllowed,
            ConnectionState connectionState,
            Map<String, String> updatedPublicValues,
            Map<String, String> updatedAdminValues,
            Set<String> removedPublicKeys,
            Set<String> removedAdminKeys,
            List<ServiceHealth> updatedServiceHealth,
            Set<String> removedServiceHealthIds,
            List<String> serviceHealthOrder) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.fromRevision = Objects.requireNonNull(fromRevision, "fromRevision");
        this.toRevision = Objects.requireNonNull(toRevision, "toRevision");
        if (!toRevision.isAfter(fromRevision)) {
            throw new IllegalArgumentException("Diagnostics delta must move forward");
        }
        this.locale = Objects.requireNonNull(locale, "locale");
        this.streamerMode = streamerMode;
        this.adminAllowed = adminAllowed && !streamerMode;
        this.connectionState = Objects.requireNonNull(connectionState, "connectionState");
        this.updatedPublicValues = copyValues(updatedPublicValues);
        this.updatedAdminValues = this.adminAllowed ? copyValues(updatedAdminValues) : Map.of();
        this.removedPublicKeys = copyKeys(removedPublicKeys);
        // A permission/streamer downgrade is represented by removals even
        // though the target snapshot no longer permits admin values.
        this.removedAdminKeys = copyKeys(removedAdminKeys);
        this.updatedServiceHealth = copyHealth(updatedServiceHealth);
        this.removedServiceHealthIds = copyServiceIds(removedServiceHealthIds);
        this.serviceHealthOrder = copyServiceIdsAsList(serviceHealthOrder);
        Set<String> updatedHealthIds = this.updatedServiceHealth.stream()
                .map(ServiceHealth::serviceId).collect(java.util.stream.Collectors.toSet());
        if (!java.util.Collections.disjoint(updatedHealthIds, this.removedServiceHealthIds)) {
            throw new IllegalArgumentException("A health item cannot be updated and removed together");
        }
        if (!java.util.Collections.disjoint(this.updatedPublicValues.keySet(), this.removedPublicKeys)
                || !java.util.Collections.disjoint(this.updatedAdminValues.keySet(), this.removedAdminKeys)) {
            throw new IllegalArgumentException("A diagnostics field cannot be updated and removed together");
        }
    }

    public static DiagnosticsDelta of(
            SessionId sessionId,
            Revision fromRevision,
            Revision toRevision,
            LocaleTag locale,
            boolean streamerMode,
            boolean adminAllowed,
            ConnectionState connectionState,
            Map<String, String> updatedPublicValues,
            Map<String, String> updatedAdminValues,
            Set<String> removedPublicKeys,
            Set<String> removedAdminKeys,
            List<ServiceHealth> updatedServiceHealth,
            Set<String> removedServiceHealthIds,
            List<String> serviceHealthOrder) {
        return new DiagnosticsDelta(
                sessionId, fromRevision, toRevision, locale, streamerMode, adminAllowed,
                connectionState, updatedPublicValues, updatedAdminValues,
                removedPublicKeys, removedAdminKeys, updatedServiceHealth,
                removedServiceHealthIds, serviceHealthOrder);
    }

    /**
     * Source-compatible constructor for callers from the bootstrap slice.
     * The supplied health values are treated as changed values; new callers
     * should use the explicit update/remove/order constructor.
     */
    public static DiagnosticsDelta of(
            SessionId sessionId,
            Revision fromRevision,
            Revision toRevision,
            LocaleTag locale,
            boolean streamerMode,
            boolean adminAllowed,
            ConnectionState connectionState,
            Map<String, String> updatedPublicValues,
            Map<String, String> updatedAdminValues,
            Set<String> removedPublicKeys,
            Set<String> removedAdminKeys,
            List<ServiceHealth> updatedServiceHealth) {
        return of(sessionId, fromRevision, toRevision, locale, streamerMode, adminAllowed,
                connectionState, updatedPublicValues, updatedAdminValues,
                removedPublicKeys, removedAdminKeys, updatedServiceHealth, Set.of(), List.of());
    }

    public static DiagnosticsDelta of(
            SessionId sessionId,
            Revision fromRevision,
            Revision toRevision,
            LocaleTag locale,
            boolean streamerMode,
            boolean adminAllowed,
            ConnectionState connectionState,
            Map<String, String> updatedPublicValues,
            Map<String, String> updatedAdminValues,
            Set<String> removedPublicKeys,
            Set<String> removedAdminKeys) {
        return of(sessionId, fromRevision, toRevision, locale, streamerMode, adminAllowed,
                connectionState, updatedPublicValues, updatedAdminValues,
                removedPublicKeys, removedAdminKeys, List.of(), Set.of(), List.of());
    }

    /** Builds a delta containing only changed, added, and removed health items. */
    public static DiagnosticsDelta between(DiagnosticsSnapshot previous, DiagnosticsSnapshot current) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        if (!previous.sessionId().equals(current.sessionId())) {
            throw new IllegalArgumentException("Diagnostics delta cannot cross sessions");
        }
        Map<String, String> updatedPublic = changed(previous.publicValues(), current.publicValues());
        Map<String, String> updatedAdmin = changed(previous.adminValues(), current.adminValues());
        Set<String> removedPublic = removed(previous.publicValues(), current.publicValues());
        Set<String> removedAdmin = removed(previous.adminValues(), current.adminValues());

        Map<String, ServiceHealth> oldHealth = byId(previous.serviceHealth());
        Map<String, ServiceHealth> newHealth = byId(current.serviceHealth());
        List<ServiceHealth> updatedHealth = current.serviceHealth().stream()
                .filter(item -> !item.equals(oldHealth.get(item.serviceId())))
                .toList();
        Set<String> removedHealth = new LinkedHashSet<>(oldHealth.keySet());
        removedHealth.removeAll(newHealth.keySet());
        List<String> oldOrderAfterChanges = previous.serviceHealth().stream()
                .map(ServiceHealth::serviceId)
                .filter(newHealth::containsKey)
                .toList();
        List<String> newOrder = current.serviceHealth().stream()
                .map(ServiceHealth::serviceId)
                .toList();
        List<String> order = oldOrderAfterChanges.equals(newOrder) ? List.of() : newOrder;

        return of(current.sessionId(), previous.revision(), current.revision(), current.locale(),
                current.streamerMode(), current.adminAllowed(), current.connectionState(),
                updatedPublic, updatedAdmin, removedPublic, removedAdmin,
                updatedHealth, removedHealth, order);
    }

    public SessionId sessionId() {
        return sessionId;
    }

    public Revision fromRevision() {
        return fromRevision;
    }

    public Revision toRevision() {
        return toRevision;
    }

    public Revision revision() {
        return toRevision;
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

    public Map<String, String> updatedPublicValues() {
        return updatedPublicValues;
    }

    public Map<String, String> updatedAdminValues() {
        return updatedAdminValues;
    }

    public Set<String> removedPublicKeys() {
        return removedPublicKeys;
    }

    public Set<String> removedAdminKeys() {
        return removedAdminKeys;
    }

    /** Only changed or newly added health values, in server registration order. */
    public List<ServiceHealth> updatedServiceHealth() {
        return updatedServiceHealth;
    }

    public Set<String> removedServiceHealthIds() {
        return removedServiceHealthIds;
    }

    /** Non-empty only when the server registration order itself changed. */
    public List<String> serviceHealthOrder() {
        return serviceHealthOrder;
    }

    /** @deprecated use {@link #updatedServiceHealth()} for delta semantics. */
    @Deprecated
    public List<ServiceHealth> serviceHealth() {
        return updatedServiceHealth;
    }

    /** Applies the health-only delta while retaining unchanged health values. */
    List<ServiceHealth> applyServiceHealth(List<ServiceHealth> previous) {
        Map<String, ServiceHealth> byId = byId(previous);
        removedServiceHealthIds.forEach(byId::remove);
        updatedServiceHealth.forEach(item -> byId.put(item.serviceId(), item));
        if (!serviceHealthOrder.isEmpty()) {
            if (!new HashSet<>(serviceHealthOrder).equals(byId.keySet())) {
                throw new IllegalArgumentException("health_order_set_mismatch");
            }
            return serviceHealthOrder.stream().map(id -> {
                ServiceHealth item = byId.get(id);
                if (item == null) {
                    throw new IllegalArgumentException("health_order_missing_item");
                }
                return item;
            }).toList();
        }
        java.util.ArrayList<ServiceHealth> result = new java.util.ArrayList<>();
        for (ServiceHealth item : previous) {
            ServiceHealth replacement = byId.get(item.serviceId());
            if (replacement != null) {
                result.add(replacement);
                byId.remove(item.serviceId());
            }
        }
        updatedServiceHealth.forEach(item -> {
            if (byId.remove(item.serviceId()) != null) {
                result.add(item);
            }
        });
        return List.copyOf(result);
    }

    public GuiDelta guiDelta() {
        Map<String, String> updated = new LinkedHashMap<>(updatedPublicValues);
        updatedAdminValues.forEach((key, value) -> updated.put("admin." + key, value));
        Set<String> removed = new HashSet<>(removedPublicKeys);
        removedAdminKeys.forEach(key -> removed.add("admin." + key));
        return GuiDelta.of(sessionId, fromRevision, toRevision, locale, streamerMode, updated, removed);
    }

    private static Map<String, String> changed(Map<String, String> previous, Map<String, String> current) {
        Map<String, String> changed = new LinkedHashMap<>();
        current.forEach((key, value) -> {
            if (!value.equals(previous.get(key))) {
                changed.put(key, value);
            }
        });
        return Map.copyOf(changed);
    }

    private static Set<String> removed(Map<String, String> previous, Map<String, String> current) {
        Set<String> removed = new HashSet<>(previous.keySet());
        removed.removeAll(current.keySet());
        return Set.copyOf(removed);
    }

    private static Map<String, ServiceHealth> byId(List<ServiceHealth> health) {
        Map<String, ServiceHealth> result = new LinkedHashMap<>();
        for (ServiceHealth item : health) {
            if (result.put(item.serviceId(), item) != null) {
                throw new IllegalArgumentException("duplicate_service_health");
            }
        }
        return result;
    }

    private static List<ServiceHealth> copyHealth(List<ServiceHealth> health) {
        Objects.requireNonNull(health, "updatedServiceHealth");
        if (health.size() > DiagnosticsSnapshot.MAX_SERVICES) {
            throw new IllegalArgumentException("Diagnostics delta has too many services");
        }
        if (health.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Diagnostics delta health cannot contain null");
        }
        byId(health);
        return List.copyOf(health);
    }

    private static Set<String> copyServiceIds(Set<String> ids) {
        Objects.requireNonNull(ids, "removedServiceHealthIds");
        if (ids.size() > DiagnosticsSnapshot.MAX_SERVICES) {
            throw new IllegalArgumentException("Diagnostics delta has too many services");
        }
        ids.forEach(DiagnosticsDelta::validateServiceId);
        return Set.copyOf(ids);
    }

    private static List<String> copyServiceIdsAsList(List<String> ids) {
        Objects.requireNonNull(ids, "serviceHealthOrder");
        if (ids.size() > DiagnosticsSnapshot.MAX_SERVICES) {
            throw new IllegalArgumentException("Diagnostics delta has too many services");
        }
        Set<String> unique = new HashSet<>();
        for (String id : ids) {
            validateServiceId(id);
            if (!unique.add(id)) {
                throw new IllegalArgumentException("duplicate_service_health");
            }
        }
        return List.copyOf(ids);
    }

    private static void validateServiceId(String id) {
        if (id == null || !SERVICE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Diagnostics service id is invalid");
        }
    }

    private static Map<String, String> copyValues(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        if (values.size() > DiagnosticsSnapshot.MAX_FIELDS) {
            throw new IllegalArgumentException("Diagnostics delta has too many fields");
        }
        values.forEach((key, value) -> {
            if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}")
                    || value == null || value.length() > DiagnosticsSnapshot.MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("Diagnostics delta field is invalid");
            }
        });
        return Map.copyOf(values);
    }

    private static Set<String> copyKeys(Set<String> keys) {
        Objects.requireNonNull(keys, "keys");
        if (keys.size() > DiagnosticsSnapshot.MAX_FIELDS) {
            throw new IllegalArgumentException("Diagnostics delta has too many keys");
        }
        keys.forEach(key -> {
            if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}")) {
                throw new IllegalArgumentException("Diagnostics delta key is invalid");
            }
        });
        return Set.copyOf(keys);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DiagnosticsDelta that
                && sessionId.equals(that.sessionId)
                && fromRevision.equals(that.fromRevision)
                && toRevision.equals(that.toRevision)
                && locale.equals(that.locale)
                && streamerMode == that.streamerMode
                && adminAllowed == that.adminAllowed
                && connectionState == that.connectionState
                && updatedPublicValues.equals(that.updatedPublicValues)
                && updatedAdminValues.equals(that.updatedAdminValues)
                && removedPublicKeys.equals(that.removedPublicKeys)
                && removedAdminKeys.equals(that.removedAdminKeys)
                && updatedServiceHealth.equals(that.updatedServiceHealth)
                && removedServiceHealthIds.equals(that.removedServiceHealthIds)
                && serviceHealthOrder.equals(that.serviceHealthOrder);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, fromRevision, toRevision, locale, streamerMode,
                adminAllowed, connectionState, updatedPublicValues, updatedAdminValues,
                removedPublicKeys, removedAdminKeys, updatedServiceHealth,
                removedServiceHealthIds, serviceHealthOrder);
    }

    @Override
    public String toString() {
        return "DiagnosticsDelta[fromRevision=" + fromRevision + ", toRevision=" + toRevision
                + ", updatedCount=" + (updatedPublicValues.size() + updatedAdminValues.size())
                + ", removedCount=" + (removedPublicKeys.size() + removedAdminKeys.size())
                + ", healthDeltaCount=" + (updatedServiceHealth.size() + removedServiceHealthIds.size()) + "]";
    }
}
