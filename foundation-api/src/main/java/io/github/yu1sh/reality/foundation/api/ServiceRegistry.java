package io.github.yu1sh.reality.foundation.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe typed service registry with deterministic ownership metadata and
 * close semantics. Registration order is retained; close and unregister
 * invoke resources in reverse registration order. An owner may contribute
 * more than one service; only the logical service id is globally unique.
 */
public final class ServiceRegistry implements AutoCloseable {
    public static final int MAX_SERVICES = 64;
    private final Object monitor = new Object();
    private final LinkedHashMap<ServiceKey<?>, Entry<?>> entries = new LinkedHashMap<>();
    private final LinkedHashMap<String, ServiceKey<?>> keysByLogicalId = new LinkedHashMap<>();
    private boolean closed;

    public <T> void register(ServiceKey<T> key, T service, String owner) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(service, "service");
        requireOwner(owner);
        synchronized (monitor) {
            ensureOpen();
            if (entries.containsKey(key)) {
                throw new IllegalStateException("A service is already registered for key " + key.id());
            }
            if (keysByLogicalId.containsKey(key.id())) {
                throw new IllegalStateException(
                        "A service is already registered for logical id " + key.id());
            }
            if (entries.size() >= MAX_SERVICES) {
                throw new IllegalStateException("Service registry capacity is exhausted");
            }
            Entry<T> entry = new Entry<>(key, service, owner);
            entries.put(key, entry);
            keysByLogicalId.put(key.id(), key);
        }
    }

    public <T> T require(ServiceKey<T> key) {
        Objects.requireNonNull(key, "key");
        synchronized (monitor) {
            ensureOpen();
            Entry<?> entry = entries.get(key);
            if (entry == null) {
                rejectTypeMismatchIfLogicalIdExists(key);
                throw new IllegalStateException("No service is registered for key " + key.id());
            }
            return key.type().cast(entry.service);
        }
    }

    public <T> Optional<T> find(ServiceKey<T> key) {
        Objects.requireNonNull(key, "key");
        synchronized (monitor) {
            ensureOpen();
            Entry<?> entry = entries.get(key);
            if (entry == null) {
                rejectTypeMismatchIfLogicalIdExists(key);
            }
            return entry == null
                    ? Optional.empty()
                    : Optional.of(key.type().cast(entry.service));
        }
    }

    /** Removes and closes one service. The close happens outside the lock. */
    public <T> Optional<T> unregister(ServiceKey<T> key) {
        Objects.requireNonNull(key, "key");
        Entry<?> entry;
        synchronized (monitor) {
            ensureOpen();
            entry = entries.remove(key);
            if (entry == null) {
                rejectTypeMismatchIfLogicalIdExists(key);
                return Optional.empty();
            }
            keysByLogicalId.remove(entry.key.id());
        }
        closeEntry(entry);
        return Optional.of(key.type().cast(entry.service));
    }

    public List<ServiceDescriptor> descriptors() {
        synchronized (monitor) {
            ensureOpen();
            List<ServiceDescriptor> result = new ArrayList<>();
            for (Entry<?> entry : entries.values()) {
                result.add(new ServiceDescriptor(
                        entry.key.id(), entry.owner, entry.service.getClass().getName()));
            }
            return List.copyOf(result);
        }
    }

    /**
     * Returns health in registration order. The registry lock is held only
     * while copying the entries; user-owned {@link HealthAwareService#health}
     * callbacks run after the lock is released. The copied registration set is
     * evaluated even if an entry is unregistered or the registry is closed
     * while callbacks are running. This snapshot-time rule avoids deadlocks
     * and makes close/unregister races deterministic without invoking a
     * callback while holding the registry monitor.
     */
    public List<ServiceHealth> healthSnapshot() {
        List<Entry<?>> snapshot;
        synchronized (monitor) {
            ensureOpen();
            snapshot = List.copyOf(entries.values());
        }

        List<ServiceHealth> result = new ArrayList<>();
        for (Entry<?> entry : snapshot) {
            if (entry.service instanceof HealthAwareService healthAware) {
                try {
                    ServiceHealth health = healthAware.health();
                    result.add(health == null
                            ? ServiceHealth.of(entry.key.id(), ServiceHealth.Status.UNAVAILABLE,
                            "foundation.health.unavailable")
                            : health.serviceId().equals(entry.key.id())
                            ? health
                            : ServiceHealth.of(entry.key.id(), ServiceHealth.Status.UNAVAILABLE,
                            "foundation.health.unavailable"));
                } catch (RuntimeException failure) {
                    result.add(ServiceHealth.of(
                            entry.key.id(), ServiceHealth.Status.UNAVAILABLE,
                            "foundation.health.unavailable"));
                }
            } else {
                result.add(ServiceHealth.healthy(entry.key.id()));
            }
        }
        return List.copyOf(result);
    }

    public boolean isClosed() {
        synchronized (monitor) {
            return closed;
        }
    }

    @Override
    public void close() {
        List<Entry<?>> toClose;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            toClose = new ArrayList<>(entries.values());
            Collections.reverse(toClose);
            entries.clear();
            keysByLogicalId.clear();
        }

        RuntimeException failure = null;
        for (Entry<?> entry : toClose) {
            try {
                closeEntry(entry);
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw new ServiceRegistryCloseException("One or more foundation services failed to close", failure);
        }
    }

    private static void requireOwner(String owner) {
        if (owner == null || owner.isBlank() || owner.length() > 64 || !owner.matches("[a-z][a-z0-9_.-]*")) {
            throw new IllegalArgumentException("Service owner is invalid");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Service registry is closed");
        }
    }

    private void rejectTypeMismatchIfLogicalIdExists(ServiceKey<?> requestedKey) {
        ServiceKey<?> registeredKey = keysByLogicalId.get(requestedKey.id());
        if (registeredKey != null && !registeredKey.type().equals(requestedKey.type())) {
            throw new IllegalArgumentException(
                    "Service key type mismatch for logical id " + requestedKey.id());
        }
    }

    private static void closeEntry(Entry<?> entry) {
        if (entry.service instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception failure) {
                throw new ServiceRegistryCloseException(
                        "Service failed to close for key " + entry.key.id(), failure);
            }
        }
    }

    private static final class Entry<T> {
        private final ServiceKey<T> key;
        private final T service;
        private final String owner;

        private Entry(ServiceKey<T> key, T service, String owner) {
            this.key = key;
            this.service = service;
            this.owner = owner;
        }
    }
}
