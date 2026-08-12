package io.github.yu1sh.reality.foundation.api;

/** Explicitly distinguishes an unavailable audit implementation from success. */
public enum AuditDisposition {
    RECORDED,
    NOT_CONFIGURED,
    REJECTED
}
