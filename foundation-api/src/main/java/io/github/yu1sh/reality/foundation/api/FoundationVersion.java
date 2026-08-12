package io.github.yu1sh.reality.foundation.api;

/** Fixed compatibility coordinates for the RT1 foundation slice. */
public final class FoundationVersion {
    public static final String MOD_ID = "reality_foundation";
    public static final String MOD_VERSION = "0.1.0-SNAPSHOT";
    public static final int NETWORK_PROTOCOL = 1;
    public static final String API_SCHEMA = "foundation.api.v1";
    public static final String RELEASE_TRAIN = "rt1-foundation";
    /** Approved migration source provenance; this is not the child repository commit. */
    public static final String REALITY_CORE_REF =
            "5e04ebc27c12d5b26b2a495e685d6ddf0bb21e22";

    private FoundationVersion() {
    }
}
