package io.github.yu1sh.reality.foundation.api;

/** Non-sensitive registry metadata exposed to diagnostics consumers. */
public record ServiceDescriptor(String serviceId, String owner, String implementationType) {
    public ServiceDescriptor {
        if (serviceId == null || owner == null || implementationType == null) {
            throw new NullPointerException("service descriptor fields");
        }
    }
}
