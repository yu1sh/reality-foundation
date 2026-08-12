package io.github.yu1sh.reality.foundation.forge;

import io.github.yu1sh.reality.foundation.api.AuditPort;
import io.github.yu1sh.reality.foundation.api.FoundationServiceContributorRegistry;
import io.github.yu1sh.reality.foundation.api.RealityServerContext;
import net.minecraft.server.MinecraftServer;

import java.time.Clock;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Identity map for the Forge server lifecycle. A server gets one context at
 * starting and the context is removed before cleanup at stopping. No static
 * feature singleton is shared between server instances.
 *
 * <p>Start is a two-phase operation. The identity reservation is made under
 * this monitor, while context creation and external contributor callbacks run
 * outside it. A concurrent stop marks that reservation cancelled; the start
 * commit then closes the unpublished context instead of exposing partial
 * state. A second start for the same identity is rejected until the first
 * reservation has completed, which makes stop/start races deterministic.
 */
public final class RealityServerContextManager {
    private final Object monitor = new Object();
    private final IdentityHashMap<MinecraftServer, FoundationServerState> states = new IdentityHashMap<>();
    private final IdentityHashMap<MinecraftServer, StartReservation> starting = new IdentityHashMap<>();

    public void start(
            MinecraftServer server,
            AuditPort auditPort,
            FoundationServiceContributorRegistry contributors,
            FoundationServerPreferencePort preferencePort) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(auditPort, "auditPort");
        Objects.requireNonNull(contributors, "contributors");
        Objects.requireNonNull(preferencePort, "preferencePort");

        StartReservation reservation = new StartReservation();
        synchronized (monitor) {
            if (states.containsKey(server) || starting.containsKey(server)) {
                throw new IllegalStateException("A foundation context already exists for this server");
            }
            starting.put(server, reservation);
        }

        RealityServerContext context = null;
        try {
            context = RealityServerContext.create(Clock.systemUTC(), auditPort);
            // The registry copies and orders contributors before invoking
            // feature code. This callback is deliberately outside monitor.
            // A failed contribution closes the context, so no partially
            // registered feature state escapes.
            contributors.apply(context);
            FoundationServerState state = new FoundationServerState(context, preferencePort);
            boolean cancelled;
            synchronized (monitor) {
                if (starting.get(server) != reservation) {
                    throw new IllegalStateException("Foundation server start reservation was lost");
                }
                starting.remove(server);
                cancelled = reservation.cancelled;
                if (!cancelled) {
                    states.put(server, state);
                }
            }
            if (cancelled) {
                context.close();
                throw new IllegalStateException("Foundation server start was cancelled");
            }
        } catch (RuntimeException failure) {
            synchronized (monitor) {
                if (starting.get(server) == reservation) {
                    starting.remove(server);
                }
            }
            if (context != null && !context.isClosed()) {
                try {
                    context.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    public void stop(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        FoundationServerState state;
        synchronized (monitor) {
            StartReservation reservation = starting.get(server);
            if (reservation != null) {
                reservation.cancelled = true;
            }
            state = states.remove(server);
        }
        if (state != null) {
            state.close();
        }
    }

    public Optional<FoundationServerState> find(MinecraftServer server) {
        synchronized (monitor) {
            return Optional.ofNullable(states.get(server));
        }
    }

    public int size() {
        synchronized (monitor) {
            return states.size();
        }
    }

    private static final class StartReservation {
        private boolean cancelled;
    }
}
