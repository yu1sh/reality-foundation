package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.identity.OperationId;
import io.github.yu1sh.reality.identity.RequestId;
import io.github.yu1sh.reality.identity.SessionId;
import io.github.yu1sh.reality.mutation.MutationRequestMetadata;
import io.github.yu1sh.reality.version.Revision;

import java.util.Objects;

/**
 * Required correlation and optimistic-concurrency metadata for a Foundation
 * management mutation. It is deliberately separate from the read-only
 * refresh packet, so a recovery action cannot silently acquire a derived
 * operation ID or omit its server-issued session/revision preconditions.
 */
public final class FoundationMutationEnvelope {
    private final MutationRequestMetadata metadata;

    private FoundationMutationEnvelope(MutationRequestMetadata metadata) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
    }

    public static FoundationMutationEnvelope of(
            RequestId requestId,
            OperationId operationId,
            SessionId sessionId,
            Revision expectedVersion) {
        return new FoundationMutationEnvelope(
                MutationRequestMetadata.of(requestId, operationId, sessionId, expectedVersion));
    }

    public MutationRequestMetadata metadata() {
        return metadata;
    }

    public RequestId requestId() {
        return metadata.requestId();
    }

    public OperationId operationId() {
        return metadata.operationId();
    }

    public SessionId sessionId() {
        return metadata.sessionId();
    }

    public Revision expectedVersion() {
        return metadata.expectedVersion();
    }
}
