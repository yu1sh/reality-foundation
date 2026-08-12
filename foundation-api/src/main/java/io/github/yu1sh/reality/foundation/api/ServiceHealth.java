package io.github.yu1sh.reality.foundation.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** Public, deliberately small health projection for one registered service. */
public final class ServiceHealth {
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final Pattern MESSAGE_KEY = Pattern.compile("[a-z][a-z0-9_.-]{0,127}");

    public enum Status {
        HEALTHY,
        DEGRADED,
        UNAVAILABLE
    }

    private final String serviceId;
    private final Status status;
    private final String messageKey;

    private ServiceHealth(String serviceId, Status status, String messageKey) {
        if (serviceId == null || !KEY.matcher(serviceId).matches()) {
            throw new IllegalArgumentException("Service health id is invalid");
        }
        if (messageKey == null || !MESSAGE_KEY.matcher(messageKey).matches()) {
            throw new IllegalArgumentException("Service health message key is invalid");
        }
        this.serviceId = serviceId;
        this.status = Objects.requireNonNull(status, "status");
        this.messageKey = messageKey;
    }

    public static ServiceHealth of(String serviceId, Status status, String messageKey) {
        return new ServiceHealth(serviceId, status, messageKey);
    }

    public static ServiceHealth healthy(String serviceId) {
        return new ServiceHealth(serviceId, Status.HEALTHY, "foundation.health.healthy");
    }

    public String serviceId() {
        return serviceId;
    }

    public Status status() {
        return status;
    }

    public String messageKey() {
        return messageKey;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ServiceHealth that
                && serviceId.equals(that.serviceId)
                && status == that.status
                && messageKey.equals(that.messageKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serviceId, status, messageKey);
    }

    @Override
    public String toString() {
        return "ServiceHealth[id=" + serviceId + ", status=" + status + ", messageKey=" + messageKey + "]";
    }
}
