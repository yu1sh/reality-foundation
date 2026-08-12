package io.github.yu1sh.reality.foundation.api;

/** Optional registry service contract for a public health projection. */
public interface HealthAwareService extends AutoCloseable {
    ServiceHealth health();

    @Override
    default void close() {
        // Most health projections have no resources. Resource-owning services
        // override this method and are closed in registry order.
    }
}
