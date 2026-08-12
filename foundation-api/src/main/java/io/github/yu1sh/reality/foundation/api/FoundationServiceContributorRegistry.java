package io.github.yu1sh.reality.foundation.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Bounded, instance-owned contributor registry used by a server runtime.
 * Registration is allowed only before the first server start. The immutable
 * ordered snapshot is reused for each later server restart, so Forge event
 * listener ordering is not part of the contract.
 */
public final class FoundationServiceContributorRegistry {
    public static final int MAX_CONTRIBUTORS = 32;
    private final Object monitor = new Object();
    private final Map<String, FoundationServiceContributor> contributors = new TreeMap<>();
    private boolean frozen;

    /** Registers one contributor before the first server context starts. */
    public void register(FoundationServiceContributor contributor) {
        Objects.requireNonNull(contributor, "contributor");
        String id = contributor.id();
        if (id == null || id.isBlank() || id.length() > 64
                || !id.matches("[a-z][a-z0-9._-]*")) {
            throw new IllegalArgumentException("Contributor id is invalid");
        }
        synchronized (monitor) {
            if (frozen) {
                throw new IllegalStateException("Contributor registry is frozen after server start");
            }
            if (contributors.containsKey(id)) {
                throw new IllegalArgumentException("Duplicate foundation contributor: " + id);
            }
            if (contributors.size() >= MAX_CONTRIBUTORS) {
                throw new IllegalStateException("Foundation contributor limit exceeded");
            }
            contributors.put(id, contributor);
        }
    }

    /** Freezes registration and returns the deterministic contributor order. */
    public List<FoundationServiceContributor> freezeAndSnapshot() {
        synchronized (monitor) {
            frozen = true;
            return List.copyOf(contributors.values());
        }
    }

    /** Returns the already-frozen list for a later server restart. */
    public List<FoundationServiceContributor> snapshotForRestart() {
        synchronized (monitor) {
            if (!frozen) {
                throw new IllegalStateException("Contributor registry is not frozen");
            }
            return List.copyOf(contributors.values());
        }
    }

    /**
     * Applies all contributors. Any partial registrations are rolled back by
     * closing the context before a stable contributor exception is thrown.
     */
    public void apply(RealityServerContext context) {
        Objects.requireNonNull(context, "context");
        List<FoundationServiceContributor> ordered;
        synchronized (monitor) {
            if (!frozen) {
                frozen = true;
            }
            ordered = new ArrayList<>(contributors.values());
        }
        for (FoundationServiceContributor contributor : ordered) {
            try {
                contributor.contribute(context);
            } catch (Throwable failure) {
                try {
                    context.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw new FoundationServiceContributorException(contributor.id(), failure);
            }
        }
    }

    public int size() {
        synchronized (monitor) {
            return contributors.size();
        }
    }
}
