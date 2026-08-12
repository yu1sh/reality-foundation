package io.github.yu1sh.reality.foundation.api;

import java.util.Objects;

/** Exact client/server compatibility tuple exchanged before state sync. */
public record FoundationHandshake(
        int networkProtocol,
        String apiSchema,
        String modVersion,
        String releaseTrain) {

    public FoundationHandshake {
        if (networkProtocol < 1) {
            throw new IllegalArgumentException("Network protocol must be positive");
        }
        requireToken("apiSchema", apiSchema, 64);
        requireToken("modVersion", modVersion, 64);
        requireToken("releaseTrain", releaseTrain, 64);
    }

    public static FoundationHandshake current() {
        return new FoundationHandshake(
                FoundationVersion.NETWORK_PROTOCOL,
                FoundationVersion.API_SCHEMA,
                FoundationVersion.MOD_VERSION,
                FoundationVersion.RELEASE_TRAIN);
    }

    private static void requireToken(String name, String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
