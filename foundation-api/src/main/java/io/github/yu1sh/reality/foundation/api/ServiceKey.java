package io.github.yu1sh.reality.foundation.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** A typed, stable key for a service owned by one adapter or feature. */
public final class ServiceKey<T> {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

    private final String id;
    private final Class<T> type;

    private ServiceKey(String id, Class<T> type) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Service key id is invalid");
        }
        this.id = id;
        this.type = Objects.requireNonNull(type, "type");
    }

    public static <T> ServiceKey<T> of(String id, Class<T> type) {
        return new ServiceKey<>(id, type);
    }

    public String id() {
        return id;
    }

    public Class<T> type() {
        return type;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ServiceKey<?> that && id.equals(that.id) && type.equals(that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type);
    }

    @Override
    public String toString() {
        return "ServiceKey[id=" + id + ", type=" + type.getName() + "]";
    }
}
