package io.github.yu1sh.reality.foundation.api;

/** Stable startup failure when a feature contributor cannot be applied. */
public final class FoundationServiceContributorException extends RuntimeException {
    private final String contributorId;

    public FoundationServiceContributorException(String contributorId, Throwable cause) {
        super("Foundation service contributor failed: " + contributorId, cause);
        this.contributorId = contributorId;
    }

    public String contributorId() {
        return contributorId;
    }
}
