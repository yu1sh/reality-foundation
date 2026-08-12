package io.github.yu1sh.reality.foundation.api;

/** Aggregated failure after deterministic registry cleanup attempted all services. */
public final class ServiceRegistryCloseException extends RuntimeException {
    public ServiceRegistryCloseException(String message, Throwable cause) {
        super(message, cause);
    }
}
