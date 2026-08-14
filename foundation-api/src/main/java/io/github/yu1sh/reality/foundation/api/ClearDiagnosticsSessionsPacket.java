package io.github.yu1sh.reality.foundation.api;

import java.util.Objects;

/**
 * Untrusted client request for the server-authoritative diagnostics recovery
 * operation. The envelope is a set of correlation and optimistic-concurrency
 * inputs; the server still reconstructs the actor and validates every field.
 */
public record ClearDiagnosticsSessionsPacket(FoundationMutationEnvelope envelope)
        implements FoundationPacket {
    public ClearDiagnosticsSessionsPacket {
        Objects.requireNonNull(envelope, "envelope");
    }

    @Override
    public int discriminator() {
        return 8;
    }
}
