package io.github.yu1sh.reality.foundation.api;

/**
 * Minecraft-independent composition hook for a feature Mod. Contributors are
 * registered during common Mod construction and applied in deterministic
 * contributor-id order whenever a new server context is created. A
 * contributor owns the service instances it registers; the context closes
 * those instances during server stop.
 */
public interface FoundationServiceContributor {
    /** Stable, non-sensitive contributor identity. */
    String id();

    /**
     * Registers this contributor's services in the supplied server context.
     * Implementations must not retain the context or use static feature state.
     */
    void contribute(RealityServerContext context);
}
