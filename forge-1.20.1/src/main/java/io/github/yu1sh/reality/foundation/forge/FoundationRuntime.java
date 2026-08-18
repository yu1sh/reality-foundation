package io.github.yu1sh.reality.foundation.forge;

import io.github.yu1sh.reality.foundation.api.AuditPort;
import io.github.yu1sh.reality.foundation.api.FoundationServiceContributor;
import io.github.yu1sh.reality.foundation.api.FoundationServiceContributorRegistry;
import io.github.yu1sh.reality.foundation.api.NoopAuditPort;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;

import java.util.Objects;
import java.util.Optional;

/**
 * Owns per-MinecraftServer state and all pre-start composition choices. The
 * instance is the integration point for reality-audit, preference owners,
 * and feature contributors; no choice is stored as static feature state.
 */
public final class FoundationRuntime {
    private final Object lifecycleMonitor = new Object();
    private final RealityServerContextManager contexts = new RealityServerContextManager();
    private final FoundationServiceContributorRegistry contributors =
            new FoundationServiceContributorRegistry();
    private final FoundationIntegrationHealth integrationHealth = new FoundationIntegrationHealth();
    private final DefaultFoundationServerPreferencePort defaultPreferences =
            new DefaultFoundationServerPreferencePort();
    private FoundationServerPreferencePort preferencePort = defaultPreferences;
    private String preferenceOwner = "foundation-default";
    private AuditPort auditPort = new NoopAuditPort();
    private String auditOwner = "foundation-default";
    private boolean auditPortInstalled;
    private boolean preferencePortInstalled;
    private boolean started;

    public FoundationRuntime() {
        contributors.register(integrationHealth);
    }

    public void processInterModMessages(InterModProcessEvent event) {
        integrationHealth.processInterModMessages(event);
    }

    public void serverStarting(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        AuditPort selectedAuditPort;
        FoundationServerPreferencePort selectedPreferencePort;
        synchronized (lifecycleMonitor) {
            // Freeze contributor registration before any contributor callback
            // can run. A failed startup is terminal for this runtime instance;
            // Forge must construct a new runtime rather than changing wiring
            // while a server lifecycle is in flight.
            started = true;
            selectedAuditPort = auditPort;
            selectedPreferencePort = preferencePort;
        }
        integrationHealth.bindServer(server);
        // ContextManager owns the identity reservation and invokes external
        // contributors outside its own monitor. Do not hold the composition
        // monitor across that callback either.
        try {
            contexts.start(server, selectedAuditPort, contributors, selectedPreferencePort);
        } catch (RuntimeException failure) {
            integrationHealth.clearServer(server);
            throw failure;
        }
    }

    /**
     * Installs the single server-owned locale/streamer preference owner before
     * the first server start. The default provider is safe en-US/disabled
     * configuration; a later owner may replace it only during composition.
     */
    public void installPreferencePort(String owner, FoundationServerPreferencePort port) {
        if (owner == null || owner.isBlank() || owner.length() > 64
                || !owner.matches("[a-z][a-z0-9._-]*")) {
            throw new IllegalArgumentException("Preference owner is invalid");
        }
        Objects.requireNonNull(port, "port");
        synchronized (lifecycleMonitor) {
            if (started) {
                throw new IllegalStateException(
                        "Preference port cannot be installed after server start");
            }
            if (preferencePortInstalled) {
                throw new IllegalStateException(
                        "Preference port already installed by " + preferenceOwner);
            }
            preferencePort = port;
            preferenceOwner = owner;
            preferencePortInstalled = true;
        }
    }

    public void serverStopping(MinecraftServer server) {
        MinecraftServer checked = Objects.requireNonNull(server, "server");
        try {
            contexts.stop(checked);
        } finally {
            integrationHealth.clearServer(checked);
        }
    }

    /**
     * Installs the single audit owner before the first server start. A
     * restart reuses the installed port; replacing it after start is rejected
     * so a management callback cannot change underneath an active context.
     */
    public void installAuditPort(String owner, AuditPort port) {
        if (owner == null || owner.isBlank() || owner.length() > 64
                || !owner.matches("[a-z][a-z0-9._-]*")) {
            throw new IllegalArgumentException("Audit owner is invalid");
        }
        Objects.requireNonNull(port, "port");
        synchronized (lifecycleMonitor) {
            if (started) {
                throw new IllegalStateException("Audit port cannot be installed after server start");
            }
            if (auditPortInstalled) {
                throw new IllegalStateException("Audit port already installed by " + auditOwner);
            }
            auditPort = port;
            auditOwner = owner;
            auditPortInstalled = true;
        }
    }

    /** Registers a feature contributor before the first server start. */
    public void registerServiceContributor(FoundationServiceContributor contributor) {
        synchronized (lifecycleMonitor) {
            if (started) {
                throw new IllegalStateException(
                        "Service contributors cannot be registered after server start");
            }
            contributors.register(contributor);
        }
    }

    /** Public composition point for a later server-owned preference provider. */
    public FoundationServerPreferencePort preferencePort() {
        synchronized (lifecycleMonitor) {
            return preferencePort;
        }
    }

    public String preferenceOwner() {
        synchronized (lifecycleMonitor) {
            return preferenceOwner;
        }
    }

    public String auditOwner() {
        synchronized (lifecycleMonitor) {
            return auditOwner;
        }
    }

    public int contributorCount() {
        return contributors.size();
    }

    public Optional<FoundationServerState> state(MinecraftServer server) {
        return contexts.find(server);
    }

    public Optional<FoundationServerState> state(ServerPlayer player) {
        return state(player.server);
    }

    public void playerLoggedOut(ServerPlayer player) {
        state(player).ifPresent(state -> state.removePlayer(player));
    }

    public int serverCount() {
        return contexts.size();
    }
}
