package io.github.yu1sh.reality.foundation.api;

/** Built-in health entry proving the foundation context is alive. */
public final class FoundationHealthService implements HealthAwareService {
    @Override
    public ServiceHealth health() {
        return ServiceHealth.healthy("foundation.health");
    }
}
