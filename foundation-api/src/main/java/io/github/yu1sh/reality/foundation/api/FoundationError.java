package io.github.yu1sh.reality.foundation.api;

import java.util.Map;

/** Error projection with bounded, non-sensitive parameters. */
public final class FoundationError {
    private final FoundationErrorCode code;
    private final Map<String, String> parameters;

    private FoundationError(FoundationErrorCode code, Map<String, String> parameters) {
        this.code = code;
        this.parameters = Map.copyOf(parameters);
    }

    public static FoundationError of(FoundationErrorCode code) {
        return new FoundationError(code, Map.of());
    }

    public static FoundationError of(FoundationErrorCode code, Map<String, String> parameters) {
        if (parameters.size() > 8) {
            throw new IllegalArgumentException("Foundation error supports at most eight parameters");
        }
        parameters.forEach((key, value) -> {
            if (key == null || value == null || key.length() > 64 || value.length() > 128
                    || !key.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
                throw new IllegalArgumentException("Foundation error parameter is invalid");
            }
        });
        return new FoundationError(code, parameters);
    }

    public FoundationErrorCode code() {
        return code;
    }

    public String codeValue() {
        return code.code();
    }

    public String messageKey() {
        return code.messageKey();
    }

    public Map<String, String> parameters() {
        return parameters;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FoundationError that
                && code == that.code
                && parameters.equals(that.parameters);
    }

    @Override
    public int hashCode() {
        return 31 * code.hashCode() + parameters.hashCode();
    }

    @Override
    public String toString() {
        return "FoundationError[code=" + codeValue() + ", parameterCount=" + parameters.size() + "]";
    }
}
