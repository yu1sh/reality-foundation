package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.identity.ActorId;
import io.github.yu1sh.reality.identity.OperationId;
import io.github.yu1sh.reality.identity.RequestId;
import io.github.yu1sh.reality.identity.SessionId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side application boundary shared by packet handlers and commands.
 *
 * <p>State decisions use {@code monitor} as their happens-before boundary.
 * Query and audit callbacks are deliberately outside that monitor. Each
 * request reserves a bounded actor slot and consumes that actor's rate
 * window, invokes callbacks, then re-enters the monitor to verify the
 * reservation, lifecycle, session, and generation before committing. A
 * callback failure or lifecycle race removes the reservation while retaining
 * the bounded rate window (unless an explicit logout/expiry cleanup reclaims
 * it), and never leaves a ghost session.
 *
 * <p>There is at most one active session per actor. A successful open replaces
 * that actor's previous session. At most {@value #MAX_ACTIVE_SESSIONS} active
 * sessions and in-flight new-actor reservations exist in one context.
 */
public final class DiagnosticsApplicationService implements AutoCloseable {
    public static final Duration DEFAULT_SESSION_LIFETIME = Duration.ofMinutes(10);
    public static final Duration DEFAULT_RATE_LIMIT = Duration.ofMillis(250);
    public static final int MAX_ACTIVE_SESSIONS = 64;
    public static final int MAX_RATE_WINDOW_ACTORS = 256;
    public static final int MAX_RECOVERY_IDEMPOTENCY_RECORDS = 128;
    public static final Duration DEFAULT_RECOVERY_IDEMPOTENCY_LIFETIME = Duration.ofMinutes(10);

    /** Package-private callback seam for query-failure and callback-race tests. */
    @FunctionalInterface
    interface SnapshotQuery {
        DiagnosticsSnapshot snapshot(
                SessionId sessionId, AuthenticatedActor actor, ConnectionState connectionState);
    }

    private final Object monitor = new Object();
    private final SnapshotQuery query;
    private final AuditPort auditPort;
    private final Clock clock;
    private final Duration sessionLifetime;
    private final Duration rateLimit;
    private final int maxRecoveryIdempotencyRecords;
    private final Duration recoveryIdempotencyLifetime;
    private final Map<SessionId, SessionState> sessions = new HashMap<>();
    private final Map<ActorId, SessionId> activeSessionByActor = new HashMap<>();
    // Actor-identifiable requests may fail before they obtain a session, so
    // this ledger has its own fixed bound rather than inheriting the session
    // bound. New actors at capacity fail closed instead of evicting another
    // actor's still-live rate evidence.
    private final LinkedHashMap<ActorId, Instant> lastRequestAt = new LinkedHashMap<>();
    private final Map<ActorId, Reservation> inFlightByActor = new HashMap<>();
    private final LinkedHashMap<OperationId, RecoveryIdempotencyRecord> recoveryIdempotency =
            new LinkedHashMap<>();
    // Bounded by the bounded active/in-flight actor set. It prevents two
    // callbacks bearing one operation ID from reaching the audit port or
    // mutation commit concurrently before the replay record exists.
    private final Map<OperationId, RecoveryFingerprint> recoveryInFlightByOperation = new HashMap<>();
    private long nextGeneration;
    private boolean closed;

    public DiagnosticsApplicationService(
            FoundationDiagnosticsQuery query,
            AuditPort auditPort,
            Clock clock) {
        this(queryAdapter(query), auditPort, clock, DEFAULT_SESSION_LIFETIME, DEFAULT_RATE_LIMIT);
    }

    public DiagnosticsApplicationService(
            FoundationDiagnosticsQuery query,
            AuditPort auditPort,
            Clock clock,
            Duration sessionLifetime,
            Duration rateLimit) {
        this(queryAdapter(query), auditPort, clock, sessionLifetime, rateLimit,
                MAX_RECOVERY_IDEMPOTENCY_RECORDS,
                DEFAULT_RECOVERY_IDEMPOTENCY_LIFETIME);
    }

    /**
     * Constructor with bounded recovery replay storage for deterministic
     * tests and server composition. Records are intentionally in-memory: a
     * server restart never claims to preserve idempotency history.
     */
    public DiagnosticsApplicationService(
            FoundationDiagnosticsQuery query,
            AuditPort auditPort,
            Clock clock,
            Duration sessionLifetime,
            Duration rateLimit,
            int maxRecoveryIdempotencyRecords,
            Duration recoveryIdempotencyLifetime) {
        this(queryAdapter(query), auditPort, clock, sessionLifetime, rateLimit,
                maxRecoveryIdempotencyRecords, recoveryIdempotencyLifetime);
    }

    DiagnosticsApplicationService(
            SnapshotQuery query,
            AuditPort auditPort,
            Clock clock,
            Duration sessionLifetime,
            Duration rateLimit) {
        this(query, auditPort, clock, sessionLifetime, rateLimit,
                MAX_RECOVERY_IDEMPOTENCY_RECORDS,
                DEFAULT_RECOVERY_IDEMPOTENCY_LIFETIME);
    }

    DiagnosticsApplicationService(
            SnapshotQuery query,
            AuditPort auditPort,
            Clock clock,
            Duration sessionLifetime,
            Duration rateLimit,
            int maxRecoveryIdempotencyRecords,
            Duration recoveryIdempotencyLifetime) {
        this.query = Objects.requireNonNull(query, "query");
        this.auditPort = Objects.requireNonNull(auditPort, "auditPort");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (sessionLifetime.isNegative() || sessionLifetime.isZero()
                || rateLimit.isNegative() || rateLimit.isZero()
                || recoveryIdempotencyLifetime.isNegative()
                || recoveryIdempotencyLifetime.isZero()
                || maxRecoveryIdempotencyRecords <= 0) {
            throw new IllegalArgumentException(
                    "Session, rate, and recovery idempotency bounds must be positive");
        }
        this.sessionLifetime = sessionLifetime;
        this.rateLimit = rateLimit;
        this.maxRecoveryIdempotencyRecords = maxRecoveryIdempotencyRecords;
        this.recoveryIdempotencyLifetime = recoveryIdempotencyLifetime;
    }

    private static SnapshotQuery queryAdapter(FoundationDiagnosticsQuery query) {
        FoundationDiagnosticsQuery checked = Objects.requireNonNull(query, "query");
        return checked::snapshot;
    }

    public DiagnosticsOpenResult open(
            DiagnosticsOpenRequest request,
            AuthenticatedActor actor,
            HandshakeDecision handshake,
            ConnectionState connectionState) {
        if (request == null || actor == null || handshake == null || connectionState == null) {
            return DiagnosticsOpenResult.denied(FoundationError.of(FoundationErrorCode.MALFORMED_REQUEST));
        }

        final Instant now;
        try {
            now = clock.instant();
        } catch (RuntimeException failure) {
            return internalOpenResult();
        }

        Reservation reservation;
        SessionId sessionId = SessionId.of(UUID.randomUUID().toString());
        synchronized (monitor) {
            if (closed) {
                return internalOpenResult();
            }
            try {
                purgeExpiredLocked(now);
                boolean rateAvailable = consumeRateLocked(actor.actorId(), now);
                if (!handshake.accepted()) {
                    return DiagnosticsOpenResult.denied(
                            FoundationError.of(FoundationErrorCode.HANDSHAKE_REQUIRED));
                }
                if (inFlightByActor.containsKey(actor.actorId()) || !rateAvailable) {
                    return DiagnosticsOpenResult.denied(FoundationError.of(FoundationErrorCode.RATE_LIMITED));
                }
                boolean replacing = activeSessionByActor.containsKey(actor.actorId());
                if (!replacing && sessions.size() + inFlightByActor.size() >= MAX_ACTIVE_SESSIONS) {
                    return DiagnosticsOpenResult.denied(FoundationError.of(
                            FoundationErrorCode.RESOURCE_LIMITED));
                }
                reservation = reserveLocked(actor.actorId(), Reservation.Kind.OPEN, sessionId,
                        now.plus(sessionLifetime));
            } catch (RuntimeException failure) {
                return internalOpenResult();
            }
        }

        DiagnosticsSnapshot snapshot;
        try {
            snapshot = query.snapshot(sessionId, actor, connectionState);
            if (!sessionId.equals(snapshot.sessionId())) {
                throw new IllegalStateException("query_session_mismatch");
            }
            auditExternal(new DiagnosticAuditEvent(
                    request.requestId(), null, actor.actorId(), "diagnostics.open", now));
        } catch (RuntimeException failure) {
            removeReservation(reservation);
            return internalOpenResult();
        }

        final Instant commitNow;
        try {
            commitNow = clock.instant();
        } catch (RuntimeException failure) {
            removeReservation(reservation);
            return internalOpenResult();
        }
        synchronized (monitor) {
            if (closed || inFlightByActor.get(actor.actorId()) != reservation) {
                removeReservationLocked(reservation);
                return internalOpenResult();
            }
            try {
                purgeExpiredLocked(commitNow);
                // The session lease begins when the request is reserved, not
                // after an arbitrary-duration query/audit callback. Never
                // publish a session that has already expired while that
                // callback was running.
                if (!commitNow.isBefore(reservation.expiresAt)) {
                    removeReservationLocked(reservation);
                    return internalOpenResult();
                }
                SessionId previous = activeSessionByActor.put(actor.actorId(), sessionId);
                if (previous != null) {
                    sessions.remove(previous);
                }
                sessions.put(sessionId, new SessionState(
                        actor.actorId(), snapshot, reservation.expiresAt));
                inFlightByActor.remove(actor.actorId());
                return DiagnosticsOpenResult.accepted(snapshot);
            } catch (RuntimeException failure) {
                removeReservationLocked(reservation);
                return internalOpenResult();
            }
        }
    }

    /**
     * Refreshes one server-owned session. At the exact expiry instant an
     * otherwise matching active session is removed and returns
     * {@link FoundationErrorCode#SESSION_EXPIRED}; an unknown, replaced, or
     * actor-mismatched session remains the non-disclosing
     * {@link FoundationErrorCode#INVALID_SESSION} result.
     */
    public DiagnosticsRefreshResult refresh(
            DiagnosticsRefreshRequest request,
            AuthenticatedActor actor,
            HandshakeDecision handshake,
            ConnectionState connectionState) {
        if (request == null || actor == null || handshake == null || connectionState == null) {
            return DiagnosticsRefreshResult.denied(FoundationError.of(FoundationErrorCode.MALFORMED_REQUEST));
        }

        final Instant now;
        try {
            now = clock.instant();
        } catch (RuntimeException failure) {
            return internalRefreshResult();
        }

        Reservation reservation;
        DiagnosticsSnapshot previous;
        synchronized (monitor) {
            if (closed) {
                return internalRefreshResult();
            }
            try {
                SessionState candidate = sessions.get(request.sessionId());
                boolean ownActiveSessionExpired = candidate != null
                        && candidate.actor.equals(actor.actorId())
                        && request.sessionId().equals(activeSessionByActor.get(actor.actorId()))
                        && !now.isBefore(candidate.expiresAt);
                purgeExpiredLocked(now);
                boolean rateAvailable = consumeRateLocked(actor.actorId(), now);
                if (!handshake.accepted()) {
                    return DiagnosticsRefreshResult.denied(FoundationError.of(
                            FoundationErrorCode.HANDSHAKE_REQUIRED));
                }
                if (ownActiveSessionExpired) {
                    return DiagnosticsRefreshResult.denied(FoundationError.of(
                            FoundationErrorCode.SESSION_EXPIRED));
                }
                SessionState state = sessions.get(request.sessionId());
                if (state == null || !state.actor.equals(actor.actorId())
                        || !request.sessionId().equals(activeSessionByActor.get(actor.actorId()))) {
                    return DiagnosticsRefreshResult.denied(FoundationError.of(
                            FoundationErrorCode.INVALID_SESSION));
                }
                if (!state.snapshot.revision().equals(request.expectedRevision())) {
                    return DiagnosticsRefreshResult.denied(FoundationError.of(
                            FoundationErrorCode.REVISION_CONFLICT,
                            Map.of("currentRevision", state.snapshot.revision().toString())));
                }
                if (inFlightByActor.containsKey(actor.actorId()) || !rateAvailable) {
                    return DiagnosticsRefreshResult.denied(FoundationError.of(
                            FoundationErrorCode.RATE_LIMITED));
                }
                previous = state.snapshot;
                reservation = reserveLocked(
                        actor.actorId(), Reservation.Kind.REFRESH, request.sessionId(), state.expiresAt);
            } catch (RuntimeException failure) {
                return internalRefreshResult();
            }
        }

        DiagnosticsSnapshot observed;
        DiagnosticsSnapshot current;
        DiagnosticsDelta delta = null;
        boolean unchanged;
        try {
            observed = query.snapshot(request.sessionId(), actor, connectionState);
            if (!request.sessionId().equals(observed.sessionId())) {
                throw new IllegalStateException("query_session_mismatch");
            }
            unchanged = sameVisibleProjection(previous, observed);
            if (unchanged) {
                current = previous;
            } else {
                current = observed.withRevision(nextRevisionAfter(previous, observed));
                delta = DiagnosticsDelta.between(previous, current);
            }
            auditExternal(new DiagnosticAuditEvent(
                    request.requestId(), null, actor.actorId(), "diagnostics.refresh", now));
        } catch (RuntimeException failure) {
            removeReservation(reservation);
            return internalRefreshResult();
        }

        final Instant commitNow;
        try {
            commitNow = clock.instant();
        } catch (RuntimeException failure) {
            removeReservation(reservation);
            return internalRefreshResult();
        }
        synchronized (monitor) {
            if (closed) {
                removeReservationLocked(reservation);
                return internalRefreshResult();
            }
            if (inFlightByActor.get(actor.actorId()) != reservation) {
                // A recovery clear or a successful reopen invalidated this
                // callback; it must not apply its old projection.
                return DiagnosticsRefreshResult.denied(FoundationError.of(
                        FoundationErrorCode.INVALID_SESSION));
            }
            SessionState state = sessions.get(request.sessionId());
            boolean ownActiveSessionExpired = state != null
                    && state.actor.equals(actor.actorId())
                    && request.sessionId().equals(activeSessionByActor.get(actor.actorId()))
                    && !commitNow.isBefore(state.expiresAt);
            purgeExpiredLocked(commitNow);
            if (ownActiveSessionExpired) {
                removeReservationLocked(reservation);
                return DiagnosticsRefreshResult.denied(FoundationError.of(
                        FoundationErrorCode.SESSION_EXPIRED));
            }
            state = sessions.get(request.sessionId());
            if (state == null || state.snapshot != previous
                    || !request.sessionId().equals(activeSessionByActor.get(actor.actorId()))) {
                removeReservationLocked(reservation);
                return DiagnosticsRefreshResult.denied(FoundationError.of(
                        FoundationErrorCode.INVALID_SESSION));
            }
            if (!state.snapshot.revision().equals(request.expectedRevision())) {
                removeReservationLocked(reservation);
                return DiagnosticsRefreshResult.denied(FoundationError.of(
                        FoundationErrorCode.REVISION_CONFLICT,
                        Map.of("currentRevision", state.snapshot.revision().toString())));
            }
            state.snapshot = current;
            inFlightByActor.remove(actor.actorId());
            return unchanged
                    ? DiagnosticsRefreshResult.unchanged()
                    : DiagnosticsRefreshResult.delta(delta);
        }
    }

    /** Command/read-only status path backed by the same application query. */
    public DiagnosticsStatusResult status(
            RequestId requestId,
            AuthenticatedActor actor,
            HandshakeDecision handshake,
            ConnectionState connectionState) {
        if (requestId == null || actor == null || handshake == null || connectionState == null) {
            return DiagnosticsStatusResult.denied(FoundationError.of(FoundationErrorCode.MALFORMED_REQUEST));
        }
        final Instant now;
        try {
            now = clock.instant();
        } catch (RuntimeException failure) {
            return DiagnosticsStatusResult.denied(FoundationError.of(
                    FoundationErrorCode.INTERNAL_FAILURE));
        }
        SessionId statusSession = SessionId.of(
                "status-" + Integer.toHexString(requestId.value().hashCode()));
        synchronized (monitor) {
            if (closed) {
                return DiagnosticsStatusResult.denied(FoundationError.of(
                        FoundationErrorCode.INTERNAL_FAILURE));
            }
            try {
                purgeExpiredLocked(now);
                boolean rateAvailable = consumeRateLocked(actor.actorId(), now);
                if (!handshake.accepted()) {
                    return DiagnosticsStatusResult.denied(FoundationError.of(
                            FoundationErrorCode.HANDSHAKE_REQUIRED));
                }
                if (actor.permissionLevel() < 2) {
                    return DiagnosticsStatusResult.denied(FoundationError.of(
                            FoundationErrorCode.PERMISSION_DENIED));
                }
                if (!rateAvailable) {
                    return DiagnosticsStatusResult.denied(FoundationError.of(
                            FoundationErrorCode.RATE_LIMITED));
                }
            } catch (RuntimeException failure) {
                return DiagnosticsStatusResult.denied(FoundationError.of(
                        FoundationErrorCode.INTERNAL_FAILURE));
            }
        }
        try {
            DiagnosticsSnapshot snapshot = query.snapshot(statusSession, actor, connectionState);
            auditExternal(new DiagnosticAuditEvent(
                    requestId, null, actor.actorId(), "diagnostics.status", now));
            synchronized (monitor) {
                return closed
                        ? DiagnosticsStatusResult.denied(FoundationError.of(
                        FoundationErrorCode.INTERNAL_FAILURE))
                        : DiagnosticsStatusResult.accepted(snapshot);
            }
        } catch (RuntimeException failure) {
            return DiagnosticsStatusResult.denied(FoundationError.of(
                    FoundationErrorCode.INTERNAL_FAILURE));
        }
    }

    /**
     * Invalidates exactly the active session owned by the server-authenticated
     * actor. A stale menu, a different actor, or a repeated lifecycle signal
     * cannot remove another session. Any refresh callback already outside the
     * monitor observes the reservation invalidation at commit and cannot
     * recreate the session.
     */
    public DiagnosticsSessionInvalidationResult invalidateSession(
            ActorId actor, SessionId sessionId) {
        if (actor == null || sessionId == null) {
            return DiagnosticsSessionInvalidationResult.of(
                    DiagnosticsSessionInvalidationResult.Kind.MALFORMED);
        }
        synchronized (monitor) {
            if (closed) {
                return DiagnosticsSessionInvalidationResult.of(
                        DiagnosticsSessionInvalidationResult.Kind.CLOSED);
            }
            SessionState state = sessions.get(sessionId);
            if (state == null) {
                Reservation reservation = inFlightByActor.get(actor);
                if (reservation != null && sessionId.equals(reservation.sessionId)) {
                    inFlightByActor.remove(actor);
                    lastRequestAt.remove(actor);
                    return DiagnosticsSessionInvalidationResult.of(
                            DiagnosticsSessionInvalidationResult.Kind.CANCELLED);
                }
                return DiagnosticsSessionInvalidationResult.of(
                        DiagnosticsSessionInvalidationResult.Kind.NOT_FOUND);
            }
            if (!state.actor.equals(actor)
                    || !sessionId.equals(activeSessionByActor.get(actor))) {
                return DiagnosticsSessionInvalidationResult.of(
                        DiagnosticsSessionInvalidationResult.Kind.ACTOR_MISMATCH);
            }
            sessions.remove(sessionId);
            activeSessionByActor.remove(actor, sessionId);
            lastRequestAt.remove(actor);
            inFlightByActor.remove(actor);
            return DiagnosticsSessionInvalidationResult.of(
                    DiagnosticsSessionInvalidationResult.Kind.INVALIDATED);
        }
    }

    /** Invalidates the one active session for a server-authenticated actor. */
    public DiagnosticsSessionInvalidationResult invalidateActorSession(ActorId actor) {
        if (actor == null) {
            return DiagnosticsSessionInvalidationResult.of(
                    DiagnosticsSessionInvalidationResult.Kind.MALFORMED);
        }
        synchronized (monitor) {
            if (closed) {
                return DiagnosticsSessionInvalidationResult.of(
                        DiagnosticsSessionInvalidationResult.Kind.CLOSED);
            }
            SessionId sessionId = activeSessionByActor.get(actor);
            if (sessionId == null) {
                Reservation reservation = inFlightByActor.remove(actor);
                if (reservation != null) {
                    lastRequestAt.remove(actor);
                    return DiagnosticsSessionInvalidationResult.of(
                            DiagnosticsSessionInvalidationResult.Kind.CANCELLED);
                }
                return DiagnosticsSessionInvalidationResult.of(
                        DiagnosticsSessionInvalidationResult.Kind.NOT_FOUND);
            }
            SessionState state = sessions.get(sessionId);
            if (state == null || !state.actor.equals(actor)) {
                activeSessionByActor.remove(actor);
                lastRequestAt.remove(actor);
                inFlightByActor.remove(actor);
                return DiagnosticsSessionInvalidationResult.of(
                        DiagnosticsSessionInvalidationResult.Kind.NOT_FOUND);
            }
            sessions.remove(sessionId);
            activeSessionByActor.remove(actor, sessionId);
            lastRequestAt.remove(actor);
            inFlightByActor.remove(actor);
            return DiagnosticsSessionInvalidationResult.of(
                    DiagnosticsSessionInvalidationResult.Kind.INVALIDATED);
        }
    }

    /**
     * Returns an envelope for the caller's current server-issued diagnostics
     * session. It exists for trusted adapters such as the command path; the
     * envelope is never sent to a client as proof of permission. A GUI action
     * uses its already server-issued snapshot to create the exact same
     * envelope, then invokes {@link #clearSessions(FoundationMutationEnvelope,
     * AuthenticatedActor, HandshakeDecision, ConnectionState)}.
     */
    public FoundationMutationEnvelope recoveryEnvelopeForActiveSession(
            RequestId requestId, OperationId operationId, AuthenticatedActor actor) {
        if (requestId == null || operationId == null || actor == null) {
            return null;
        }
        final Instant now;
        try {
            now = clock.instant();
        } catch (RuntimeException failure) {
            return null;
        }
        synchronized (monitor) {
            if (closed) {
                return null;
            }
            try {
                SessionState candidate = activeStateForActorLocked(actor.actorId(), now);
                return candidate == null ? null : FoundationMutationEnvelope.of(
                        requestId, operationId,
                        activeSessionByActor.get(actor.actorId()), candidate.snapshot.revision());
            } catch (RuntimeException failure) {
                return null;
            }
        }
    }

    /**
     * Clears sessions as a server-authoritative, idempotent management
     * mutation. The caller identity and permission projection are supplied by
     * the server adapter, never by packet data. Every well-formed attempt
     * consumes one bounded actor rate-window entry before returning a stable
     * success or failure. The audit callback is outside the monitor, while
     * the commit phase repeats lifecycle, session, revision, permission, and
     * expiry validation before it mutates any state.
     *
     * <p>Replay history is intentionally in memory and bounded. It prevents
     * duplicate execution only while this server context remains alive; it is
     * not a claim of restart-spanning persistence.
     */
    public DiagnosticsRecoveryResult clearSessions(
            FoundationMutationEnvelope envelope,
            AuthenticatedActor actor,
            HandshakeDecision handshake,
            ConnectionState connectionState) {
        if (envelope == null || actor == null || handshake == null || connectionState == null) {
            return recoveryDenied(FoundationErrorCode.MALFORMED_REQUEST, AuditDisposition.REJECTED);
        }

        final Instant now;
        try {
            now = clock.instant();
        } catch (RuntimeException failure) {
            return internalRecoveryResult();
        }

        final RecoveryFingerprint fingerprint = new RecoveryFingerprint(
                actor.actorId(), envelope.requestId(), envelope.sessionId(), envelope.expectedVersion());
        final Reservation reservation;
        synchronized (monitor) {
            if (closed) {
                return internalRecoveryResult();
            }
            try {
                purgeRecoveryIdempotencyLocked(now);
                boolean rateAvailable = consumeRateLocked(actor.actorId(), now);
                if (!handshake.accepted()) {
                    return recoveryDenied(FoundationErrorCode.HANDSHAKE_REQUIRED,
                            AuditDisposition.REJECTED);
                }
                if (actor.permissionLevel() < 4) {
                    return recoveryDenied(FoundationErrorCode.PERMISSION_DENIED,
                            AuditDisposition.REJECTED);
                }
                RecoveryIdempotencyRecord replay = recoveryIdempotency.get(envelope.operationId());
                if (replay != null) {
                    if (!replay.fingerprint.equals(fingerprint)) {
                        return recoveryDenied(FoundationErrorCode.OPERATION_CONFLICT,
                                AuditDisposition.REJECTED);
                    }
                    // A retry is still an authenticated request attempt, so
                    // consumeRateLocked ran above. It intentionally replays
                    // the first stored result without another audit callback
                    // or a second state mutation.
                    return replay.result;
                }
                RecoveryFingerprint inFlight = recoveryInFlightByOperation.get(envelope.operationId());
                if (inFlight != null) {
                    if (!inFlight.equals(fingerprint)) {
                        return recoveryDenied(FoundationErrorCode.OPERATION_CONFLICT,
                                AuditDisposition.REJECTED);
                    }
                    // The initial callback owns this operation ID. A retry
                    // consumed rate above but cannot duplicate its audit or
                    // mutation before a stored result becomes available.
                    return recoveryDenied(FoundationErrorCode.RATE_LIMITED,
                            AuditDisposition.REJECTED);
                }
                if (inFlightByActor.containsKey(actor.actorId()) || !rateAvailable) {
                    return recoveryDenied(FoundationErrorCode.RATE_LIMITED,
                            AuditDisposition.REJECTED);
                }
                FoundationError validationError = validateRecoverySessionLocked(envelope, actor, now);
                if (validationError != null) {
                    DiagnosticsRecoveryResult result = DiagnosticsRecoveryResult.denied(
                            validationError, AuditDisposition.REJECTED);
                    storeRecoveryIdempotencyLocked(envelope.operationId(), fingerprint, result, now);
                    return result;
                }
                SessionState state = sessions.get(envelope.sessionId());
                reservation = reserveLocked(actor.actorId(), Reservation.Kind.RECOVERY,
                        envelope.sessionId(), state.expiresAt);
                recoveryInFlightByOperation.put(envelope.operationId(), fingerprint);
            } catch (RuntimeException failure) {
                return internalRecoveryResult();
            }
        }

        final AuditDisposition disposition;
        try {
            disposition = auditExternal(new DiagnosticAuditEvent(
                    envelope.requestId(), envelope.operationId(), actor.actorId(),
                    "diagnostics.recovery.clear_sessions", now));
        } catch (RuntimeException failure) {
            return finishRecoveryWithoutCommit(reservation, envelope.operationId(), fingerprint,
                    internalRecoveryResult(), now);
        }
        if (disposition != AuditDisposition.RECORDED) {
            return finishRecoveryWithoutCommit(reservation, envelope.operationId(), fingerprint,
                    recoveryDenied(FoundationErrorCode.INTERNAL_FAILURE, disposition), now);
        }

        final Instant commitNow;
        try {
            commitNow = clock.instant();
        } catch (RuntimeException failure) {
            return finishRecoveryWithoutCommit(reservation, envelope.operationId(), fingerprint,
                    internalRecoveryResult(), now);
        }
        synchronized (monitor) {
            DiagnosticsRecoveryResult result;
            try {
                if (closed || inFlightByActor.get(actor.actorId()) != reservation) {
                    result = internalRecoveryResult();
                } else if (!handshake.accepted()) {
                    result = recoveryDenied(FoundationErrorCode.HANDSHAKE_REQUIRED,
                            AuditDisposition.REJECTED);
                } else if (actor.permissionLevel() < 4) {
                    result = recoveryDenied(FoundationErrorCode.PERMISSION_DENIED,
                            AuditDisposition.REJECTED);
                } else {
                    FoundationError validationError = validateRecoverySessionLocked(
                            envelope, actor, commitNow);
                    if (validationError != null) {
                        result = DiagnosticsRecoveryResult.denied(validationError,
                                AuditDisposition.REJECTED);
                    } else {
                        sessions.clear();
                        activeSessionByActor.clear();
                        // Keep rate timestamps: a recovery success must not
                        // erase rate evidence from failed or successful
                        // requests. Expiry cleanup reclaims them after the
                        // bounded window has elapsed.
                        inFlightByActor.clear();
                        nextGeneration = Math.incrementExact(nextGeneration);
                        result = DiagnosticsRecoveryResult.accepted(disposition);
                    }
                }
            } catch (RuntimeException failure) {
                result = internalRecoveryResult();
            }
            completeRecoveryOperationLocked(envelope.operationId(), fingerprint, result, commitNow);
            return result;
        }
    }

    public DiagnosticsCountResult sessionCount() {
        synchronized (monitor) {
            if (closed) {
                return DiagnosticsCountResult.denied(
                        FoundationError.of(FoundationErrorCode.INTERNAL_FAILURE));
            }
            try {
                purgeExpiredLocked(clock.instant());
                return DiagnosticsCountResult.accepted(sessions.size());
            } catch (RuntimeException failure) {
                return DiagnosticsCountResult.denied(
                        FoundationError.of(FoundationErrorCode.INTERNAL_FAILURE));
            }
        }
    }

    /** Package-private observability for bounded-ledger regression tests. */
    int recoveryIdempotencyRecordCount() {
        synchronized (monitor) {
            if (closed) {
                return 0;
            }
            try {
                purgeRecoveryIdempotencyLocked(clock.instant());
                return recoveryIdempotency.size();
            } catch (RuntimeException failure) {
                return 0;
            }
        }
    }

    /** Package-private observability for bounded rate-ledger regression tests. */
    int rateWindowActorCount() {
        synchronized (monitor) {
            if (closed) {
                return 0;
            }
            try {
                purgeExpiredLocked(clock.instant());
                return lastRequestAt.size();
            } catch (RuntimeException failure) {
                return 0;
            }
        }
    }

    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            sessions.clear();
            activeSessionByActor.clear();
            lastRequestAt.clear();
            inFlightByActor.clear();
            recoveryIdempotency.clear();
            recoveryInFlightByOperation.clear();
            nextGeneration = Math.incrementExact(nextGeneration);
        }
    }

    public boolean isClosed() {
        synchronized (monitor) {
            return closed;
        }
    }

    private Reservation reserveLocked(
            ActorId actor, Reservation.Kind kind, SessionId sessionId, Instant expiresAt) {
        Reservation reservation = new Reservation(++nextGeneration, actor, kind, sessionId, expiresAt);
        inFlightByActor.put(actor, reservation);
        return reservation;
    }

    /**
     * Records exactly one rate-window entry for a well-formed request and
     * returns whether it was eligible before this attempt. Updating the entry
     * even for a rejected request prevents failure-path retry storms.
     */
    private boolean consumeRateLocked(ActorId actor, Instant now) {
        Instant previous = lastRequestAt.get(actor);
        if (previous == null && lastRequestAt.size() >= MAX_RATE_WINDOW_ACTORS) {
            // Preserve every live actor's window. A new actor cannot make an
            // old failure retryable merely by forcing an LRU eviction.
            return false;
        }
        boolean available = previous == null || !now.isBefore(previous.plus(rateLimit));
        lastRequestAt.put(actor, now);
        return available;
    }

    private void purgeExpiredLocked(Instant now) {
        Iterator<Map.Entry<SessionId, SessionState>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SessionId, SessionState> entry = iterator.next();
            if (!now.isBefore(entry.getValue().expiresAt)) {
                ActorId actor = entry.getValue().actor;
                iterator.remove();
                if (entry.getKey().equals(activeSessionByActor.get(actor))) {
                    activeSessionByActor.remove(actor);
                }
            }
        }
        Iterator<Map.Entry<ActorId, Instant>> rateIterator = lastRequestAt.entrySet().iterator();
        while (rateIterator.hasNext()) {
            Map.Entry<ActorId, Instant> entry = rateIterator.next();
            if (!activeSessionByActor.containsKey(entry.getKey())
                    && !inFlightByActor.containsKey(entry.getKey())
                    && !now.isBefore(entry.getValue().plus(rateLimit))) {
                rateIterator.remove();
            }
        }
    }

    private void removeReservation(Reservation reservation) {
        synchronized (monitor) {
            removeReservationLocked(reservation);
        }
    }

    private void removeReservationLocked(Reservation reservation) {
        if (inFlightByActor.get(reservation.actor) == reservation) {
            inFlightByActor.remove(reservation.actor);
        }
    }

    private DiagnosticsRecoveryResult finishRecoveryWithoutCommit(
            Reservation reservation,
            OperationId operationId,
            RecoveryFingerprint fingerprint,
            DiagnosticsRecoveryResult result,
            Instant now) {
        synchronized (monitor) {
            removeReservationLocked(reservation);
            completeRecoveryOperationLocked(operationId, fingerprint, result, now);
            return result;
        }
    }

    private SessionState activeStateForActorLocked(ActorId actor, Instant now) {
        SessionId sessionId = activeSessionByActor.get(actor);
        SessionState candidate = sessionId == null ? null : sessions.get(sessionId);
        boolean expired = candidate != null && !now.isBefore(candidate.expiresAt);
        purgeExpiredLocked(now);
        if (expired) {
            return null;
        }
        SessionId activeSession = activeSessionByActor.get(actor);
        SessionState state = activeSession == null ? null : sessions.get(activeSession);
        return state != null && state.actor.equals(actor) ? state : null;
    }

    /**
     * Performs the session/revision half of the recovery validation while the
     * monitor is held. It detects expiry before purge so an actor receives the
     * stable, non-disclosing SESSION_EXPIRED response only for its own active
     * session.
     */
    private FoundationError validateRecoverySessionLocked(
            FoundationMutationEnvelope envelope, AuthenticatedActor actor, Instant now) {
        SessionState candidate = sessions.get(envelope.sessionId());
        boolean ownActiveSessionExpired = candidate != null
                && candidate.actor.equals(actor.actorId())
                && envelope.sessionId().equals(activeSessionByActor.get(actor.actorId()))
                && !now.isBefore(candidate.expiresAt);
        purgeExpiredLocked(now);
        if (ownActiveSessionExpired) {
            return FoundationError.of(FoundationErrorCode.SESSION_EXPIRED);
        }
        SessionState state = sessions.get(envelope.sessionId());
        if (state == null || !state.actor.equals(actor.actorId())
                || !envelope.sessionId().equals(activeSessionByActor.get(actor.actorId()))) {
            return FoundationError.of(FoundationErrorCode.INVALID_SESSION);
        }
        if (!state.snapshot.revision().equals(envelope.expectedVersion())) {
            return FoundationError.of(FoundationErrorCode.REVISION_CONFLICT,
                    Map.of("currentRevision", state.snapshot.revision().toString()));
        }
        return null;
    }

    private void purgeRecoveryIdempotencyLocked(Instant now) {
        Iterator<Map.Entry<OperationId, RecoveryIdempotencyRecord>> iterator =
                recoveryIdempotency.entrySet().iterator();
        while (iterator.hasNext()) {
            if (!now.isBefore(iterator.next().getValue().expiresAt)) {
                iterator.remove();
            }
        }
    }

    private void storeRecoveryIdempotencyLocked(
            OperationId operationId,
            RecoveryFingerprint fingerprint,
            DiagnosticsRecoveryResult result,
            Instant now) {
        purgeRecoveryIdempotencyLocked(now);
        while (recoveryIdempotency.size() >= maxRecoveryIdempotencyRecords) {
            Iterator<OperationId> iterator = recoveryIdempotency.keySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
        recoveryIdempotency.put(operationId, new RecoveryIdempotencyRecord(
                fingerprint, result, now.plus(recoveryIdempotencyLifetime)));
    }

    private void completeRecoveryOperationLocked(
            OperationId operationId,
            RecoveryFingerprint fingerprint,
            DiagnosticsRecoveryResult result,
            Instant now) {
        recoveryInFlightByOperation.remove(operationId, fingerprint);
        storeRecoveryIdempotencyLocked(operationId, fingerprint, result, now);
    }

    private AuditDisposition auditExternal(DiagnosticAuditEvent event) {
        AuditDisposition disposition = auditPort.record(event);
        return disposition == null ? AuditDisposition.REJECTED : disposition;
    }

    private static boolean sameVisibleProjection(
            DiagnosticsSnapshot previous, DiagnosticsSnapshot observed) {
        return previous.locale().equals(observed.locale())
                && previous.streamerMode() == observed.streamerMode()
                && previous.adminAllowed() == observed.adminAllowed()
                && previous.connectionState() == observed.connectionState()
                && previous.publicValues().equals(observed.publicValues())
                && previous.adminValues().equals(observed.adminValues())
                && previous.serviceHealth().equals(observed.serviceHealth());
    }

    private static io.github.yu1sh.reality.version.Revision nextRevisionAfter(
            DiagnosticsSnapshot previous, DiagnosticsSnapshot observed) {
        long minimum = Math.incrementExact(previous.revision().value());
        long observedRevision = observed.revision().value();
        return io.github.yu1sh.reality.version.Revision.of(Math.max(minimum, observedRevision));
    }

    private static DiagnosticsOpenResult internalOpenResult() {
        return DiagnosticsOpenResult.denied(FoundationError.of(FoundationErrorCode.INTERNAL_FAILURE));
    }

    private static DiagnosticsRefreshResult internalRefreshResult() {
        return DiagnosticsRefreshResult.denied(FoundationError.of(FoundationErrorCode.INTERNAL_FAILURE));
    }

    private static DiagnosticsRecoveryResult recoveryDenied(
            FoundationErrorCode code, AuditDisposition auditDisposition) {
        return DiagnosticsRecoveryResult.denied(FoundationError.of(code), auditDisposition);
    }

    private static DiagnosticsRecoveryResult internalRecoveryResult() {
        return recoveryDenied(FoundationErrorCode.INTERNAL_FAILURE, AuditDisposition.REJECTED);
    }

    private static final class SessionState {
        private final ActorId actor;
        private final Instant expiresAt;
        private DiagnosticsSnapshot snapshot;

        private SessionState(ActorId actor, DiagnosticsSnapshot snapshot, Instant expiresAt) {
            this.actor = actor;
            this.snapshot = snapshot;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Reservation {
        private enum Kind {
            OPEN,
            REFRESH,
            RECOVERY
        }

        private final long generation;
        private final ActorId actor;
        private final Kind kind;
        private final SessionId sessionId;
        private final Instant expiresAt;

        private Reservation(
                long generation, ActorId actor, Kind kind, SessionId sessionId, Instant expiresAt) {
            this.generation = generation;
            this.actor = actor;
            this.kind = kind;
            this.sessionId = sessionId;
            this.expiresAt = expiresAt;
        }
    }

    private static final class RecoveryFingerprint {
        private final ActorId actor;
        private final RequestId requestId;
        private final SessionId sessionId;
        private final io.github.yu1sh.reality.version.Revision expectedVersion;

        private RecoveryFingerprint(
                ActorId actor,
                RequestId requestId,
                SessionId sessionId,
                io.github.yu1sh.reality.version.Revision expectedVersion) {
            this.actor = actor;
            this.requestId = requestId;
            this.sessionId = sessionId;
            this.expectedVersion = expectedVersion;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RecoveryFingerprint fingerprint)) {
                return false;
            }
            return actor.equals(fingerprint.actor)
                    && requestId.equals(fingerprint.requestId)
                    && sessionId.equals(fingerprint.sessionId)
                    && expectedVersion.equals(fingerprint.expectedVersion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(actor, requestId, sessionId, expectedVersion);
        }
    }

    private static final class RecoveryIdempotencyRecord {
        private final RecoveryFingerprint fingerprint;
        private final DiagnosticsRecoveryResult result;
        private final Instant expiresAt;

        private RecoveryIdempotencyRecord(
                RecoveryFingerprint fingerprint, DiagnosticsRecoveryResult result, Instant expiresAt) {
            this.fingerprint = fingerprint;
            this.result = result;
            this.expiresAt = expiresAt;
        }
    }
}
