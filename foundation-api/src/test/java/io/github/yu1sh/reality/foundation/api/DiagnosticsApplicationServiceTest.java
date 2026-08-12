package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.gui.LocaleTag;
import io.github.yu1sh.reality.identity.ActorId;
import io.github.yu1sh.reality.identity.OperationId;
import io.github.yu1sh.reality.identity.RequestId;
import io.github.yu1sh.reality.identity.SessionId;
import io.github.yu1sh.reality.version.Revision;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsApplicationServiceTest {
    private static final FoundationHandshake HANDSHAKE = FoundationHandshake.current();
    private static final HandshakeDecision ACCEPTED = new HandshakeValidator(HANDSHAKE).evaluate(HANDSHAKE);

    @Test
    void diagnosticsProjectionReportsTheInstalledAuditBoundary() {
        AuditPort recorded = new AuditPort() {
            @Override
            public AuditDisposition record(DiagnosticAuditEvent event) {
                return AuditDisposition.RECORDED;
            }

            @Override
            public AuditAvailability availability() {
                return AuditAvailability.CONFIGURED;
            }
        };
        FoundationDiagnosticsQuery query = new FoundationDiagnosticsQuery(
                new ServiceRegistry(), recorded);
        DiagnosticsSnapshot snapshot = query.snapshot(
                io.github.yu1sh.reality.identity.SessionId.of("audit-projection"),
                actor(ActorId.of("audit-actor"), 0, "en-US", false),
                ConnectionState.HANDSHAKE_ACCEPTED);
        assertEquals("configured", snapshot.publicValues().get("foundation.audit"));
    }

    @Test
    void diagnosticsProjectionFailsClosedForNoopNullAndThrowingAvailability() {
        FoundationDiagnosticsQuery noop = new FoundationDiagnosticsQuery(new ServiceRegistry());
        assertEquals("not_configured", noop.snapshot(
                io.github.yu1sh.reality.identity.SessionId.of("audit-noop"),
                actor(ActorId.of("audit-noop-actor"), 0, "en-US", false),
                ConnectionState.HANDSHAKE_ACCEPTED).publicValues().get("foundation.audit"));
        for (AuditPort port : new AuditPort[] {
                new AuditPort() {
                    @Override
                    public AuditDisposition record(DiagnosticAuditEvent event) {
                        return AuditDisposition.RECORDED;
                    }

                    @Override
                    public AuditAvailability availability() {
                        return null;
                    }
                },
                new AuditPort() {
                    @Override
                    public AuditDisposition record(DiagnosticAuditEvent event) {
                        return AuditDisposition.REJECTED;
                    }

                    @Override
                    public AuditAvailability availability() {
                        throw new IllegalStateException("availability callback failure");
                    }
                }}) {
            FoundationDiagnosticsQuery query = new FoundationDiagnosticsQuery(
                    new ServiceRegistry(), port);
            assertEquals("unavailable", query.snapshot(
                    io.github.yu1sh.reality.identity.SessionId.of("audit-unavailable"),
                    actor(ActorId.of("audit-unavailable-actor"), 0, "en-US", false),
                    ConnectionState.HANDSHAKE_ACCEPTED).publicValues().get("foundation.audit"));
        }
    }

    @Test
    void equalHealthValueObjectsRemainUnchangedAcrossOneHundredRefreshes() {
        MutableClock clock = new MutableClock();
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(ServiceKey.of("stable", HealthAwareService.class),
                () -> ServiceHealth.healthy("stable"), "stable-owner");
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(registry), new NoopAuditPort(), clock,
                Duration.ofMinutes(5), Duration.ofNanos(1));
        AuthenticatedActor actor = actor(ActorId.of("stable-actor"), 0, "en-US", false);
        DiagnosticsSnapshot initial = service.open(
                new DiagnosticsOpenRequest(RequestId.of("stable-open")), actor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();

        for (int index = 0; index < 100; index++) {
            clock.advance(Duration.ofNanos(1));
            DiagnosticsRefreshResult result = service.refresh(
                    new DiagnosticsRefreshRequest(RequestId.of("stable-refresh-" + index),
                            initial.sessionId(), initial.revision()),
                    actor, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED);
            assertEquals(DiagnosticsRefreshResult.Kind.UNCHANGED, result.kind(),
                    "same visible health must not create a delta at iteration " + index);
            assertTrue(result.delta().isEmpty());
        }
    }

    @Test
    void oneHealthFieldChangeAdvancesOnceThenBecomesUnchanged() {
        MutableClock clock = new MutableClock();
        ServiceRegistry registry = new ServiceRegistry();
        AtomicReference<ServiceHealth> health = new AtomicReference<>(
                ServiceHealth.healthy("changing"));
        registry.register(ServiceKey.of("changing", HealthAwareService.class),
                () -> ServiceHealth.of(health.get().serviceId(), health.get().status(),
                        health.get().messageKey()), "changing-owner");
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(registry), new NoopAuditPort(), clock,
                Duration.ofMinutes(5), Duration.ofNanos(1));
        AuthenticatedActor actor = actor(ActorId.of("health-actor"), 0, "en-US", false);
        DiagnosticsSnapshot current = service.open(
                new DiagnosticsOpenRequest(RequestId.of("health-open")), actor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();

        health.set(ServiceHealth.of("changing", ServiceHealth.Status.DEGRADED,
                "foundation.health.degraded"));
        clock.advance(Duration.ofNanos(1));
        DiagnosticsRefreshResult statusChange = service.refresh(
                new DiagnosticsRefreshRequest(RequestId.of("health-status"),
                        current.sessionId(), current.revision()),
                actor, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED);
        assertEquals(DiagnosticsRefreshResult.Kind.DELTA, statusChange.kind());
        current = current.apply(statusChange.delta().orElseThrow());
        assertEquals(ServiceHealth.Status.DEGRADED, current.serviceHealth().get(0).status());
        assertUnchangedAfter(current, service, actor, clock, "health-status-stable");

        health.set(ServiceHealth.of("changing", ServiceHealth.Status.DEGRADED,
                "foundation.health.custom"));
        clock.advance(Duration.ofNanos(1));
        DiagnosticsRefreshResult messageChange = service.refresh(
                new DiagnosticsRefreshRequest(RequestId.of("health-message"),
                        current.sessionId(), current.revision()),
                actor, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED);
        assertEquals(DiagnosticsRefreshResult.Kind.DELTA, messageChange.kind());
        current = current.apply(messageChange.delta().orElseThrow());
        assertEquals("foundation.health.custom", current.serviceHealth().get(0).messageKey());
        assertUnchangedAfter(current, service, actor, clock, "health-message-stable");

        health.set(ServiceHealth.of("dynamic", ServiceHealth.Status.DEGRADED,
                "foundation.health.custom"));
        clock.advance(Duration.ofNanos(1));
        DiagnosticsRefreshResult idChange = service.refresh(
                new DiagnosticsRefreshRequest(RequestId.of("health-id"),
                        current.sessionId(), current.revision()),
                actor, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED);
        assertEquals(DiagnosticsRefreshResult.Kind.DELTA, idChange.kind());
        current = current.apply(idChange.delta().orElseThrow());
        assertEquals("changing", current.serviceHealth().get(0).serviceId());
        assertEquals(ServiceHealth.Status.UNAVAILABLE, current.serviceHealth().get(0).status());
        assertUnchangedAfter(current, service, actor, clock, "health-id-stable");
    }

    @Test
    void visibleProjectionChangesAlwaysAdvanceAndRevokeAdminFields() {
        MutableClock clock = new MutableClock();
        ServiceRegistry registry = new ServiceRegistry();
        AtomicReference<ServiceHealth> changingHealth = new AtomicReference<>(
                ServiceHealth.healthy("dynamic"));
        registry.register(ServiceKey.of("dynamic", HealthAwareService.class),
                () -> changingHealth.get(), "dynamic-owner");
        FoundationDiagnosticsQuery query = new FoundationDiagnosticsQuery(registry);
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                query, new NoopAuditPort(), clock, Duration.ofMinutes(5), Duration.ofNanos(1));
        ActorId actorId = ActorId.of("actor-1");
        AuthenticatedActor admin = actor(actorId, 2, "en-US", false);

        DiagnosticsSnapshot original = service.open(
                new DiagnosticsOpenRequest(RequestId.of("open-1")), admin, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        assertTrue(original.adminAllowed());
        assertFalse(original.adminValues().isEmpty());

        clock.advance(Duration.ofSeconds(1));
        DiagnosticsRefreshResult permissionDrop = service.refresh(
                new DiagnosticsRefreshRequest(RequestId.of("refresh-permission"),
                        original.sessionId(), original.revision()),
                actor(actorId, 0, "en-US", false), ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED);
        assertEquals(DiagnosticsRefreshResult.Kind.DELTA, permissionDrop.kind());
        DiagnosticsDelta permissionDelta = permissionDrop.delta().orElseThrow();
        assertTrue(permissionDelta.toRevision().isAfter(permissionDelta.fromRevision()));
        assertTrue(permissionDelta.removedAdminKeys().containsAll(original.adminValues().keySet()));
        DiagnosticsSnapshot revoked = original.apply(permissionDelta);
        assertFalse(revoked.adminAllowed());
        assertTrue(revoked.adminValues().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> revoked.apply(permissionDelta));

        clock.advance(Duration.ofSeconds(1));
        DiagnosticsRefreshResult streamerDrop = service.refresh(
                new DiagnosticsRefreshRequest(RequestId.of("refresh-streamer"),
                        original.sessionId(), revoked.revision()),
                actor(actorId, 0, "en-US", true), ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED);
        assertEquals(DiagnosticsRefreshResult.Kind.DELTA, streamerDrop.kind());
        DiagnosticsSnapshot streamer = revoked.apply(streamerDrop.delta().orElseThrow());
        assertTrue(streamer.streamerMode());
        assertTrue(streamer.adminValues().isEmpty());

        clock.advance(Duration.ofSeconds(1));
        changingHealth.set(ServiceHealth.of(
                "dynamic", ServiceHealth.Status.DEGRADED, "foundation.health.degraded"));
        DiagnosticsRefreshResult healthChange = service.refresh(
                new DiagnosticsRefreshRequest(RequestId.of("refresh-health"),
                        original.sessionId(), streamer.revision()),
                actor(actorId, 0, "en-US", true), ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED);
        assertEquals(DiagnosticsRefreshResult.Kind.DELTA, healthChange.kind());
        DiagnosticsSnapshot degraded = streamer.apply(healthChange.delta().orElseThrow());
        assertTrue(degraded.serviceHealth().stream().anyMatch(item ->
                item.serviceId().equals("dynamic") && item.status() == ServiceHealth.Status.DEGRADED));

        clock.advance(Duration.ofSeconds(1));
        DiagnosticsRefreshResult connectionChange = service.refresh(
                new DiagnosticsRefreshRequest(RequestId.of("refresh-connection"),
                        original.sessionId(), degraded.revision()),
                actor(actorId, 0, "en-US", true), ACCEPTED,
                ConnectionState.REJECTED);
        DiagnosticsSnapshot disconnected = degraded.apply(connectionChange.delta().orElseThrow());
        assertEquals(ConnectionState.REJECTED, disconnected.connectionState());

        clock.advance(Duration.ofSeconds(1));
        DiagnosticsRefreshResult localeChange = service.refresh(
                new DiagnosticsRefreshRequest(RequestId.of("refresh-locale"),
                        original.sessionId(), disconnected.revision()),
                actor(actorId, 0, "ja-JP", true), ACCEPTED,
                ConnectionState.REJECTED);
        DiagnosticsSnapshot japanese = disconnected.apply(localeChange.delta().orElseThrow());
        assertEquals(LocaleTag.of("ja-JP"), japanese.locale());

        clock.advance(Duration.ofSeconds(1));
        DiagnosticsRefreshResult unchanged = service.refresh(
                new DiagnosticsRefreshRequest(RequestId.of("refresh-unchanged"),
                        original.sessionId(), japanese.revision()),
                actor(actorId, 0, "ja-JP", true), ACCEPTED,
                ConnectionState.REJECTED);
        assertEquals(DiagnosticsRefreshResult.Kind.UNCHANGED, unchanged.kind());
    }

    @Test
    void oneActorOneSessionAndExpiredActorRateStateIsReclaimed() {
        MutableClock clock = new MutableClock();
        ServiceRegistry registry = new ServiceRegistry();
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(registry), new NoopAuditPort(), clock,
                Duration.ofSeconds(1), Duration.ofNanos(1));
        AuthenticatedActor firstActor = actor(ActorId.of("actor-1"), 0, "en-US", false);
        DiagnosticsSnapshot first = service.open(
                new DiagnosticsOpenRequest(RequestId.of("first")), firstActor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        clock.advance(Duration.ofSeconds(1));
        DiagnosticsSnapshot replacement = service.open(
                new DiagnosticsOpenRequest(RequestId.of("replacement")), firstActor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        assertEquals(1, service.sessionCount().count());
        assertEquals(DiagnosticsSessionInvalidationResult.Kind.NOT_FOUND,
                service.invalidateSession(firstActor.actorId(), first.sessionId()).kind(),
                "a stale menu must not invalidate the replacement session");
        assertEquals(1, service.sessionCount().count());
        clock.advance(Duration.ofSeconds(2));
        assertEquals(0, service.sessionCount().count());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(FoundationErrorCode.INVALID_SESSION,
                service.refresh(new DiagnosticsRefreshRequest(
                        RequestId.of("old"), first.sessionId(), first.revision()),
                        firstActor, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED)
                        .error().orElseThrow().code());
        assertNotNull(replacement);
    }

    @Test
    void refreshDistinguishesOwnExpiryAtBoundaryFromUnknownOrOtherActor() {
        MutableClock clock = new MutableClock();
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), new NoopAuditPort(), clock,
                Duration.ofSeconds(5), Duration.ofNanos(1));
        AuthenticatedActor owner = actor(ActorId.of("expiry-owner"), 0, "en-US", false);
        AuthenticatedActor other = actor(ActorId.of("expiry-other"), 0, "en-US", false);
        DiagnosticsSnapshot own = service.open(new DiagnosticsOpenRequest(
                RequestId.of("expiry-open")), owner, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        clock.advance(Duration.ofSeconds(5));
        DiagnosticsRefreshResult expired = service.refresh(new DiagnosticsRefreshRequest(
                RequestId.of("expiry-boundary"), own.sessionId(), own.revision()), owner,
                ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED);
        assertEquals(FoundationErrorCode.SESSION_EXPIRED,
                expired.error().orElseThrow().code());
        assertEquals(0, service.sessionCount().count());

        DiagnosticsSnapshot replacement = service.open(new DiagnosticsOpenRequest(
                RequestId.of("expiry-replacement")), other, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        assertEquals(FoundationErrorCode.INVALID_SESSION, service.refresh(
                new DiagnosticsRefreshRequest(RequestId.of("expiry-unknown"), own.sessionId(), own.revision()),
                owner, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        assertEquals(FoundationErrorCode.INVALID_SESSION, service.refresh(
                new DiagnosticsRefreshRequest(RequestId.of("expiry-other"), replacement.sessionId(),
                        replacement.revision()), owner, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
    }

    @Test
    void logoutAndMenuInvalidationReclaimsAllCapacityAndCannotCrossActors() {
        MutableClock clock = new MutableClock();
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), new NoopAuditPort(), clock,
                Duration.ofMinutes(5), Duration.ofNanos(1));
        List<AuthenticatedActor> actors = new ArrayList<>();
        for (int index = 0; index < DiagnosticsApplicationService.MAX_ACTIVE_SESSIONS; index++) {
            AuthenticatedActor actor = actor(ActorId.of("logout-actor-" + index), 0, "en-US", false);
            actors.add(actor);
            assertTrue(service.open(new DiagnosticsOpenRequest(
                    RequestId.of("logout-open-" + index)), actor, ACCEPTED,
                    ConnectionState.HANDSHAKE_ACCEPTED).accepted());
        }
        assertEquals(DiagnosticsApplicationService.MAX_ACTIVE_SESSIONS,
                service.sessionCount().count());
        for (AuthenticatedActor actor : actors) {
            assertTrue(service.invalidateActorSession(actor.actorId()).invalidated());
            assertFalse(service.invalidateActorSession(actor.actorId()).invalidated(),
                    "logout invalidation must be idempotent");
        }
        assertEquals(0, service.sessionCount().count());
        assertTrue(service.open(new DiagnosticsOpenRequest(RequestId.of("after-logout")),
                actor(ActorId.of("after-logout-actor"), 0, "en-US", false), ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).accepted());

        DiagnosticsApplicationService crossService = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), new NoopAuditPort(), clock,
                Duration.ofMinutes(5), Duration.ofNanos(1));
        DiagnosticsSnapshot first = crossService.open(new DiagnosticsOpenRequest(
                        RequestId.of("cross-first")),
                actor(ActorId.of("cross-first-actor"), 0, "en-US", false), ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        clock.advance(Duration.ofNanos(1));
        DiagnosticsSnapshot second = crossService.open(new DiagnosticsOpenRequest(
                        RequestId.of("cross-second")),
                actor(ActorId.of("cross-second-actor"), 0, "en-US", false), ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        DiagnosticsSessionInvalidationResult mismatch = crossService.invalidateSession(
                ActorId.of("cross-first-actor"), second.sessionId());
        assertEquals(DiagnosticsSessionInvalidationResult.Kind.ACTOR_MISMATCH, mismatch.kind());
        assertEquals(2, crossService.sessionCount().count());
        assertTrue(crossService.invalidateSession(ActorId.of("cross-first-actor"), first.sessionId())
                .invalidated());
        assertEquals(1, crossService.sessionCount().count());
    }

    @Test
    void closeDuringRefreshCallbackCannotResurrectSession() throws Exception {
        MutableClock clock = new MutableClock();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean block = new AtomicBoolean(false);
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(ServiceKey.of("blocking", HealthAwareService.class), () -> {
            if (block.get()) {
                entered.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            return ServiceHealth.healthy("blocking");
        }, "blocking-owner");
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(registry), new NoopAuditPort(), clock,
                Duration.ofMinutes(5), Duration.ofNanos(1));
        AuthenticatedActor actor = actor(ActorId.of("close-refresh-actor"), 0, "en-US", false);
        DiagnosticsSnapshot snapshot = service.open(new DiagnosticsOpenRequest(
                        RequestId.of("close-refresh-open")), actor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        block.set(true);
        clock.advance(Duration.ofNanos(1));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DiagnosticsRefreshResult> future = executor.submit(() -> service.refresh(
                    new DiagnosticsRefreshRequest(RequestId.of("close-refresh"),
                            snapshot.sessionId(), snapshot.revision()),
                    actor, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            service.close();
            release.countDown();
            assertEquals(FoundationErrorCode.INTERNAL_FAILURE,
                    future.get(2, TimeUnit.SECONDS).error().orElseThrow().code());
            assertEquals(FoundationErrorCode.INTERNAL_FAILURE,
                    service.sessionCount().error().orElseThrow().code());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void logoutCancelsInitialOpenCallbackAndFreesCapacity() throws Exception {
        MutableClock clock = new MutableClock();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(ServiceKey.of("blocking-open", HealthAwareService.class), () -> {
            entered.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return ServiceHealth.healthy("blocking-open");
        }, "blocking-open-owner");
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(registry), new NoopAuditPort(), clock,
                Duration.ofMinutes(5), Duration.ofNanos(1));
        ActorId loggedOut = ActorId.of("opening-logged-out");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DiagnosticsOpenResult> future = executor.submit(() -> service.open(
                    new DiagnosticsOpenRequest(RequestId.of("opening")),
                    actor(loggedOut, 0, "en-US", false), ACCEPTED,
                    ConnectionState.HANDSHAKE_ACCEPTED));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            DiagnosticsSessionInvalidationResult cancelled = service.invalidateActorSession(loggedOut);
            assertTrue(cancelled.cancelled());
            release.countDown();
            assertEquals(FoundationErrorCode.INTERNAL_FAILURE,
                    future.get(2, TimeUnit.SECONDS).error().orElseThrow().code());
            assertEquals(0, service.sessionCount().count());
            assertTrue(service.open(new DiagnosticsOpenRequest(RequestId.of("after-logout-open")),
                    actor(ActorId.of("new-after-logout"), 0, "en-US", false), ACCEPTED,
                    ConnectionState.HANDSHAKE_ACCEPTED).accepted());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void menuCloseBoundaryCancelsInFlightRefreshWithoutResurrection() throws Exception {
        MutableClock clock = new MutableClock();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean block = new AtomicBoolean(false);
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(ServiceKey.of("blocking-refresh", HealthAwareService.class), () -> {
            if (block.get()) {
                entered.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            return ServiceHealth.healthy("blocking-refresh");
        }, "blocking-refresh-owner");
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(registry), new NoopAuditPort(), clock,
                Duration.ofMinutes(5), Duration.ofNanos(1));
        AuthenticatedActor actor = actor(ActorId.of("menu-close-actor"), 0, "en-US", false);
        DiagnosticsSnapshot snapshot = service.open(new DiagnosticsOpenRequest(
                        RequestId.of("menu-open")), actor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        block.set(true);
        clock.advance(Duration.ofNanos(1));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DiagnosticsRefreshResult> future = executor.submit(() -> service.refresh(
                    new DiagnosticsRefreshRequest(RequestId.of("menu-refresh"),
                            snapshot.sessionId(), snapshot.revision()), actor, ACCEPTED,
                    ConnectionState.HANDSHAKE_ACCEPTED));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            DiagnosticsSessionInvalidationResult invalidated = service.invalidateSession(
                    actor.actorId(), snapshot.sessionId());
            assertTrue(invalidated.invalidated());
            release.countDown();
            assertEquals(FoundationErrorCode.INVALID_SESSION,
                    future.get(2, TimeUnit.SECONDS).error().orElseThrow().code());
            assertEquals(0, service.sessionCount().count());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void thirtyActorsCanOpenInParallelAndCapacityDoesNotGrowAfterExpiry() throws Exception {
        MutableClock clock = new MutableClock();
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), new NoopAuditPort(), clock,
                Duration.ofSeconds(1), Duration.ofNanos(1));
        ExecutorService executor = Executors.newFixedThreadPool(30);
        CyclicBarrier barrier = new CyclicBarrier(30);
        try {
            List<Future<DiagnosticsOpenResult>> futures = new ArrayList<>();
            for (int index = 0; index < 30; index++) {
                int actorNumber = index;
                futures.add(executor.submit(() -> {
                    barrier.await(2, TimeUnit.SECONDS);
                    ActorId actor = ActorId.of("actor-" + actorNumber);
                    return service.open(new DiagnosticsOpenRequest(
                                    RequestId.of("parallel-" + actorNumber)),
                            actor(actor, 0, "en-US", false), ACCEPTED,
                            ConnectionState.HANDSHAKE_ACCEPTED);
                }));
            }
            for (Future<DiagnosticsOpenResult> future : futures) {
                assertTrue(future.get(3, TimeUnit.SECONDS).accepted());
            }
            assertEquals(30, service.sessionCount().count());
            clock.advance(Duration.ofSeconds(2));
            assertEquals(0, service.sessionCount().count());
            clock.advance(Duration.ofSeconds(1));
            assertTrue(service.open(new DiagnosticsOpenRequest(RequestId.of("after-expiry")),
                    actor(ActorId.of("new-actor"), 0, "en-US", false), ACCEPTED,
                    ConnectionState.HANDSHAKE_ACCEPTED).accepted());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void callbackFailureConsumesBoundedRateWindowAndReentrantAuditIsSafe() {
        MutableClock clock = new MutableClock();
        ServiceRegistry registry = new ServiceRegistry();
        AtomicBoolean fail = new AtomicBoolean(true);
        AuditPort audit = event -> {
            if (fail.getAndSet(false)) {
                throw new IllegalStateException("audit callback failure");
            }
            return AuditDisposition.NOT_CONFIGURED;
        };
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(registry), audit, clock,
                Duration.ofMinutes(1), Duration.ofNanos(1));
        AuthenticatedActor actor = actor(ActorId.of("callback-actor"), 0, "en-US", false);
        DiagnosticsOpenResult failed = service.open(
                new DiagnosticsOpenRequest(RequestId.of("failed")), actor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED);
        assertEquals(FoundationErrorCode.INTERNAL_FAILURE, failed.error().orElseThrow().code());
        assertEquals(0, service.sessionCount().count());
        assertEquals(FoundationErrorCode.RATE_LIMITED, service.open(
                new DiagnosticsOpenRequest(RequestId.of("retry-too-soon")), actor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        clock.advance(Duration.ofSeconds(1));
        assertTrue(service.open(new DiagnosticsOpenRequest(RequestId.of("retry")),
                actor, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED).accepted());

        assertEquals(FoundationErrorCode.PERMISSION_DENIED, service.status(
                RequestId.of("status-player"), actor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        assertTrue(service.status(RequestId.of("status-admin"),
                actor(ActorId.of("status-admin-actor"), 2, "en-US", false), ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).accepted());

        AtomicReference<DiagnosticsApplicationService> reference = new AtomicReference<>();
        AuditPort reentrant = event -> {
            assertTrue(reference.get().sessionCount().accepted());
            return AuditDisposition.NOT_CONFIGURED;
        };
        DiagnosticsApplicationService reentrantService = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), reentrant, clock,
                Duration.ofMinutes(1), Duration.ofNanos(1));
        reference.set(reentrantService);
        assertTrue(reentrantService.open(new DiagnosticsOpenRequest(RequestId.of("reentrant")),
                actor(ActorId.of("reentrant-actor"), 0, "en-US", false), ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).accepted());
    }

    @Test
    void slowAuditCanRaceCloseWithoutDeadlockAndRecoveryIsStable() throws Exception {
        MutableClock clock = new MutableClock();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AuditPort slowAudit = event -> {
            entered.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return AuditDisposition.NOT_CONFIGURED;
        };
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), slowAudit, clock,
                Duration.ofMinutes(1), Duration.ofNanos(1));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DiagnosticsOpenResult> future = executor.submit(() -> service.open(
                    new DiagnosticsOpenRequest(RequestId.of("close-race")),
                    actor(ActorId.of("close-race-actor"), 0, "en-US", false), ACCEPTED,
                    ConnectionState.HANDSHAKE_ACCEPTED));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            service.close();
            release.countDown();
            assertEquals(FoundationErrorCode.INTERNAL_FAILURE,
                    future.get(2, TimeUnit.SECONDS).error().orElseThrow().code());
            assertEquals(FoundationErrorCode.INTERNAL_FAILURE,
                    service.sessionCount().error().orElseThrow().code());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void callbackExpiryAndClockFailureCannotCommitOpenOrRefresh() {
        MutableClock clock = new MutableClock();
        AtomicBoolean advanceInQuery = new AtomicBoolean(true);
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(ServiceKey.of("advance-clock", HealthAwareService.class), () -> {
            if (advanceInQuery.get()) {
                clock.advance(Duration.ofSeconds(1));
            }
            return ServiceHealth.healthy("advance-clock");
        }, "clock-test");
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(registry), new NoopAuditPort(), clock,
                Duration.ofSeconds(1), Duration.ofNanos(1));
        AuthenticatedActor actor = actor(ActorId.of("callback-expiry"), 0, "en-US", false);

        DiagnosticsOpenResult expiredOpen = service.open(new DiagnosticsOpenRequest(
                RequestId.of("callback-expired-open")), actor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED);
        assertFalse(expiredOpen.accepted());
        assertEquals(FoundationErrorCode.INTERNAL_FAILURE,
                expiredOpen.error().orElseThrow().code(), "open never exposes SESSION_EXPIRED");
        assertEquals(0, service.sessionCount().count());

        advanceInQuery.set(false);
        clock.advance(Duration.ofNanos(1));
        DiagnosticsSnapshot snapshot = service.open(new DiagnosticsOpenRequest(
                RequestId.of("callback-refresh-open")), actor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        clock.advance(Duration.ofNanos(1));
        advanceInQuery.set(true);
        DiagnosticsRefreshResult expiredRefresh = service.refresh(new DiagnosticsRefreshRequest(
                RequestId.of("callback-expired-refresh"), snapshot.sessionId(), snapshot.revision()), actor,
                ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED);
        assertEquals(FoundationErrorCode.SESSION_EXPIRED,
                expiredRefresh.error().orElseThrow().code());
        assertEquals(0, service.sessionCount().count());

        MutableClock failingClock = new MutableClock();
        ServiceRegistry failingRegistry = new ServiceRegistry();
        failingRegistry.register(ServiceKey.of("fail-commit-clock", HealthAwareService.class), () -> {
            failingClock.failNextRead();
            return ServiceHealth.healthy("fail-commit-clock");
        }, "clock-test");
        DiagnosticsApplicationService failingService = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(failingRegistry), new NoopAuditPort(), failingClock,
                Duration.ofMinutes(1), Duration.ofNanos(1));
        DiagnosticsOpenResult clockFailure = failingService.open(new DiagnosticsOpenRequest(
                RequestId.of("clock-failure-open")), actor(ActorId.of("clock-failure"), 0, "en-US", false),
                ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED);
        assertEquals(FoundationErrorCode.INTERNAL_FAILURE,
                clockFailure.error().orElseThrow().code());
        assertEquals(0, failingService.sessionCount().count());
    }

    @Test
    void everyActorIdentifiableFailureConsumesExactlyOneRateWindow() {
        MutableClock clock = new MutableClock();
        Duration rate = Duration.ofSeconds(1);
        AuthenticatedActor actor = actor(ActorId.of("rate-failures"), 2, "en-US", false);
        DiagnosticsApplicationService handshakeService = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), new NoopAuditPort(), clock,
                Duration.ofMinutes(5), rate);
        HandshakeDecision rejected = HandshakeDecision.rejected(HandshakeRejectReason.MALFORMED_HANDSHAKE);
        assertEquals(FoundationErrorCode.HANDSHAKE_REQUIRED, handshakeService.open(
                new DiagnosticsOpenRequest(RequestId.of("rate-handshake")), actor, rejected,
                ConnectionState.REJECTED).error().orElseThrow().code());
        assertEquals(FoundationErrorCode.RATE_LIMITED, handshakeService.open(
                new DiagnosticsOpenRequest(RequestId.of("rate-handshake-valid")), actor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        clock.advance(rate);
        assertTrue(handshakeService.open(new DiagnosticsOpenRequest(RequestId.of("rate-handshake-after")),
                actor, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED).accepted());

        MutableClock refreshClock = new MutableClock();
        DiagnosticsApplicationService refreshService = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), new NoopAuditPort(), refreshClock,
                Duration.ofMinutes(5), rate);
        DiagnosticsSnapshot snapshot = refreshService.open(new DiagnosticsOpenRequest(
                RequestId.of("rate-refresh-open")), actor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        refreshClock.advance(rate);
        assertEquals(FoundationErrorCode.REVISION_CONFLICT, refreshService.refresh(
                new DiagnosticsRefreshRequest(RequestId.of("rate-revision"), snapshot.sessionId(),
                        Revision.of(snapshot.revision().value() + 1)), actor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        assertEquals(FoundationErrorCode.RATE_LIMITED, refreshService.refresh(
                new DiagnosticsRefreshRequest(RequestId.of("rate-revision-valid"), snapshot.sessionId(),
                        snapshot.revision()), actor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        refreshClock.advance(rate);
        assertEquals(DiagnosticsRefreshResult.Kind.UNCHANGED, refreshService.refresh(
                new DiagnosticsRefreshRequest(RequestId.of("rate-revision-after"), snapshot.sessionId(),
                        snapshot.revision()), actor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).kind());

        refreshClock.advance(Duration.ofMinutes(5));
        assertEquals(FoundationErrorCode.SESSION_EXPIRED, refreshService.refresh(
                new DiagnosticsRefreshRequest(RequestId.of("rate-expired"), snapshot.sessionId(),
                        snapshot.revision()), actor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        assertEquals(FoundationErrorCode.RATE_LIMITED, refreshService.open(
                new DiagnosticsOpenRequest(RequestId.of("rate-expired-valid")), actor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());

        MutableClock statusClock = new MutableClock();
        DiagnosticsApplicationService statusService = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), new NoopAuditPort(), statusClock,
                Duration.ofMinutes(5), rate);
        AuthenticatedActor player = actor(ActorId.of("rate-status"), 0, "en-US", false);
        assertEquals(FoundationErrorCode.PERMISSION_DENIED, statusService.status(
                RequestId.of("rate-permission"), player, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        assertEquals(FoundationErrorCode.RATE_LIMITED, statusService.status(
                RequestId.of("rate-permission-valid"), actor(ActorId.of("rate-status"), 2,
                        "en-US", false), ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED)
                .error().orElseThrow().code());
        statusClock.advance(rate);
        assertTrue(statusService.status(RequestId.of("rate-permission-after"),
                actor(ActorId.of("rate-status"), 2, "en-US", false), ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).accepted());
    }

    @Test
    void queryAndAuditFailuresConsumeOneRateWindowBeforeReturningInternalFailure() {
        Duration rate = Duration.ofSeconds(1);
        MutableClock queryClock = new MutableClock();
        AtomicBoolean queryFails = new AtomicBoolean(true);
        FoundationDiagnosticsQuery healthyQuery = new FoundationDiagnosticsQuery(new ServiceRegistry());
        DiagnosticsApplicationService queryService = new DiagnosticsApplicationService(
                (sessionId, queryActor, connectionState) -> {
                    if (queryFails.get()) {
                        throw new IllegalStateException("query failure");
                    }
                    return healthyQuery.snapshot(sessionId, queryActor, connectionState);
                }, event -> AuditDisposition.RECORDED,
                queryClock, Duration.ofMinutes(5), rate);
        AuthenticatedActor queryActor = actor(ActorId.of("rate-query-failure"), 0, "en-US", false);
        assertEquals(FoundationErrorCode.INTERNAL_FAILURE, queryService.open(new DiagnosticsOpenRequest(
                        RequestId.of("rate-query-failure")), queryActor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        queryFails.set(false);
        assertEquals(FoundationErrorCode.RATE_LIMITED, queryService.open(new DiagnosticsOpenRequest(
                        RequestId.of("rate-query-immediate")), queryActor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        queryClock.advance(rate);
        assertTrue(queryService.open(new DiagnosticsOpenRequest(RequestId.of("rate-query-after")),
                queryActor, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED).accepted());

        MutableClock auditClock = new MutableClock();
        AtomicBoolean auditFails = new AtomicBoolean(true);
        DiagnosticsApplicationService auditService = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), event -> {
                    if (auditFails.get()) {
                        throw new IllegalStateException("audit failure");
                    }
                    return AuditDisposition.RECORDED;
                }, auditClock, Duration.ofMinutes(5), rate);
        AuthenticatedActor auditActor = actor(ActorId.of("rate-audit-failure"), 0, "en-US", false);
        assertEquals(FoundationErrorCode.INTERNAL_FAILURE, auditService.open(new DiagnosticsOpenRequest(
                        RequestId.of("rate-audit-failure")), auditActor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        auditFails.set(false);
        assertEquals(FoundationErrorCode.RATE_LIMITED, auditService.open(new DiagnosticsOpenRequest(
                        RequestId.of("rate-audit-immediate")), auditActor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        auditClock.advance(rate);
        assertTrue(auditService.open(new DiagnosticsOpenRequest(RequestId.of("rate-audit-after")),
                auditActor, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED).accepted());
    }

    @Test
    void rateWindowLedgerIsBoundedAndFailsClosedUntilItsEntriesExpire() {
        MutableClock clock = new MutableClock();
        Duration rate = Duration.ofSeconds(1);
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), new NoopAuditPort(), clock,
                Duration.ofMinutes(5), rate);
        HandshakeDecision rejected = HandshakeDecision.rejected(HandshakeRejectReason.MALFORMED_HANDSHAKE);
        for (int index = 0; index < DiagnosticsApplicationService.MAX_RATE_WINDOW_ACTORS; index++) {
            AuthenticatedActor actor = actor(ActorId.of("rate-ledger-" + index), 0, "en-US", false);
            assertEquals(FoundationErrorCode.HANDSHAKE_REQUIRED, service.open(new DiagnosticsOpenRequest(
                            RequestId.of("rate-ledger-request-" + index)), actor, rejected,
                    ConnectionState.REJECTED).error().orElseThrow().code());
        }
        assertEquals(DiagnosticsApplicationService.MAX_RATE_WINDOW_ACTORS,
                service.rateWindowActorCount());
        AuthenticatedActor overflow = actor(ActorId.of("rate-ledger-overflow"), 0, "en-US", false);
        assertEquals(FoundationErrorCode.RATE_LIMITED, service.open(new DiagnosticsOpenRequest(
                        RequestId.of("rate-ledger-overflow")), overflow, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        assertEquals(DiagnosticsApplicationService.MAX_RATE_WINDOW_ACTORS,
                service.rateWindowActorCount());
        clock.advance(rate);
        assertTrue(service.open(new DiagnosticsOpenRequest(RequestId.of("rate-ledger-after-expiry")),
                overflow, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED).accepted());
        assertEquals(1, service.rateWindowActorCount());
    }

    @Test
    void recoveryRequiresServerActorActiveSessionExactRevisionAndRecordedAudit() {
        MutableClock clock = new MutableClock();
        AtomicInteger auditAttempts = new AtomicInteger();
        AuditPort audit = event -> {
            auditAttempts.incrementAndGet();
            return AuditDisposition.RECORDED;
        };
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), audit, clock,
                Duration.ofMinutes(5), Duration.ofNanos(1));
        AuthenticatedActor admin = actor(ActorId.of("recovery-admin"), 4, "en-US", false);
        DiagnosticsSnapshot snapshot = service.open(new DiagnosticsOpenRequest(
                RequestId.of("recovery-open")), admin, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        clock.advance(Duration.ofNanos(1));
        FoundationMutationEnvelope envelope = recoveryEnvelope(
                "recovery-request", "recovery-operation", snapshot);

        DiagnosticsRecoveryResult accepted = service.clearSessions(envelope, admin, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED);
        assertTrue(accepted.accepted());
        assertEquals(AuditDisposition.RECORDED, accepted.auditDisposition());
        assertEquals(0, service.sessionCount().count());
        assertEquals(2, auditAttempts.get(), "open and the first mutation are audited once");

        AuthenticatedActor player = actor(ActorId.of("recovery-player"), 0, "en-US", false);
        clock.advance(Duration.ofNanos(1));
        DiagnosticsSnapshot playerSnapshot = service.open(new DiagnosticsOpenRequest(
                RequestId.of("player-open")), player, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        clock.advance(Duration.ofNanos(1));
        DiagnosticsRecoveryResult denied = service.clearSessions(recoveryEnvelope(
                        "player-denied", "player-denied-operation", playerSnapshot), player, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED);
        assertEquals(FoundationErrorCode.PERMISSION_DENIED, denied.error().orElseThrow().code());
        assertEquals(1, service.sessionCount().count());
        assertEquals(3, auditAttempts.get(), "permission is checked from the server actor before audit");
    }

    @Test
    void recoveryReplaysExactlyOnceRejectsOperationConflictsAndBoundsLedger() {
        MutableClock clock = new MutableClock();
        AtomicInteger recoveryAuditAttempts = new AtomicInteger();
        AuditPort audit = event -> {
            if (event.operationId() != null) {
                recoveryAuditAttempts.incrementAndGet();
            }
            return AuditDisposition.RECORDED;
        };
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), audit, clock,
                Duration.ofMinutes(5), Duration.ofNanos(1), 1, Duration.ofSeconds(1));
        AuthenticatedActor first = actor(ActorId.of("replay-first"), 4, "en-US", false);
        DiagnosticsSnapshot firstSnapshot = service.open(new DiagnosticsOpenRequest(
                RequestId.of("replay-first-open")), first, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        clock.advance(Duration.ofNanos(1));
        FoundationMutationEnvelope firstEnvelope = recoveryEnvelope(
                "replay-first-request", "replay-operation", firstSnapshot);
        assertTrue(service.clearSessions(firstEnvelope, first, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).accepted());

        clock.advance(Duration.ofNanos(1));
        AuthenticatedActor survivor = actor(ActorId.of("replay-survivor"), 4, "en-US", false);
        DiagnosticsSnapshot survivorSnapshot = service.open(new DiagnosticsOpenRequest(
                RequestId.of("replay-survivor-open")), survivor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        clock.advance(Duration.ofNanos(1));
        assertTrue(service.clearSessions(firstEnvelope, first, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).accepted(), "same fingerprint replays the stored result");
        assertEquals(1, service.sessionCount().count(), "replay must not clear a later session");
        assertEquals(1, recoveryAuditAttempts.get(), "replay must not emit a second audit event");

        DiagnosticsRecoveryResult conflict = service.clearSessions(FoundationMutationEnvelope.of(
                        RequestId.of("different-request"), OperationId.of("replay-operation"),
                        survivorSnapshot.sessionId(), survivorSnapshot.revision()), survivor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED);
        assertEquals(FoundationErrorCode.OPERATION_CONFLICT, conflict.error().orElseThrow().code());
        assertEquals(1, service.sessionCount().count());

        clock.advance(Duration.ofNanos(1));
        assertTrue(service.clearSessions(recoveryEnvelope(
                        "second-request", "second-operation", survivorSnapshot), survivor, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).accepted());
        assertEquals(1, service.recoveryIdempotencyRecordCount());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(0, service.recoveryIdempotencyRecordCount());
    }

    @Test
    void recoveryFailsClosedForAuditAndSessionValidationFailures() {
        MutableClock clock = new MutableClock();
        AtomicReference<String> mode = new AtomicReference<>("recorded");
        AuditPort audit = event -> switch (mode.get()) {
            case "recorded" -> AuditDisposition.RECORDED;
            case "not-configured" -> AuditDisposition.NOT_CONFIGURED;
            case "rejected" -> AuditDisposition.REJECTED;
            case "null" -> null;
            case "throw" -> throw new IllegalStateException("audit failure");
            default -> throw new AssertionError("unknown audit mode");
        };
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), audit, clock,
                Duration.ofSeconds(1), Duration.ofNanos(1));
        AuthenticatedActor admin = actor(ActorId.of("audit-admin"), 4, "en-US", false);
        DiagnosticsSnapshot snapshot = service.open(new DiagnosticsOpenRequest(
                RequestId.of("audit-open")), admin, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        for (String failureMode : List.of("not-configured", "rejected", "null", "throw")) {
            clock.advance(Duration.ofNanos(1));
            mode.set(failureMode);
            DiagnosticsRecoveryResult result = service.clearSessions(recoveryEnvelope(
                            "audit-" + failureMode, "audit-operation-" + failureMode, snapshot), admin,
                    ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED);
            assertEquals(FoundationErrorCode.INTERNAL_FAILURE,
                    result.error().orElseThrow().code(), failureMode);
            assertEquals(1, service.sessionCount().count(),
                    "audit failure must not mutate: " + failureMode);
        }
        clock.advance(Duration.ofSeconds(1));
        assertEquals(FoundationErrorCode.SESSION_EXPIRED, service.clearSessions(recoveryEnvelope(
                        "expired", "expired-operation", snapshot), admin, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        assertEquals(0, service.sessionCount().count());
    }

    @Test
    void recoveryAuditFailureIsRecordedForReplayAndConsumesOneRateAttempt() {
        MutableClock clock = new MutableClock();
        Duration rate = Duration.ofSeconds(1);
        AtomicBoolean auditAvailable = new AtomicBoolean(false);
        AtomicInteger recoveryAuditAttempts = new AtomicInteger();
        AuditPort audit = event -> {
            if (event.operationId() != null) {
                recoveryAuditAttempts.incrementAndGet();
                return auditAvailable.get() ? AuditDisposition.RECORDED : AuditDisposition.NOT_CONFIGURED;
            }
            return AuditDisposition.RECORDED;
        };
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), audit, clock,
                Duration.ofMinutes(5), rate);
        AuthenticatedActor admin = actor(ActorId.of("recovery-audit-replay"), 4, "en-US", false);
        DiagnosticsSnapshot snapshot = service.open(new DiagnosticsOpenRequest(
                RequestId.of("recovery-audit-replay-open")), admin, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        clock.advance(rate);
        FoundationMutationEnvelope first = recoveryEnvelope(
                "recovery-audit-replay-request", "recovery-audit-replay-operation", snapshot);

        DiagnosticsRecoveryResult rejected = service.clearSessions(first, admin, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED);
        assertEquals(FoundationErrorCode.INTERNAL_FAILURE, rejected.error().orElseThrow().code());
        assertEquals(AuditDisposition.NOT_CONFIGURED, rejected.auditDisposition());
        assertEquals(1, recoveryAuditAttempts.get());
        assertEquals(1, service.sessionCount().count(), "audit failure must not mutate sessions");

        DiagnosticsRecoveryResult replay = service.clearSessions(first, admin, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED);
        assertEquals(rejected.error().orElseThrow().code(), replay.error().orElseThrow().code(),
                "same operation/fingerprint must replay first stable result");
        assertEquals(rejected.auditDisposition(), replay.auditDisposition());
        assertEquals(1, recoveryAuditAttempts.get(), "replay must not invoke audit again");
        assertEquals(FoundationErrorCode.RATE_LIMITED, service.clearSessions(recoveryEnvelope(
                        "recovery-audit-rate", "recovery-audit-rate-operation", snapshot), admin,
                ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code(),
                "the replay itself consumes one actor attempt");

        clock.advance(rate);
        auditAvailable.set(true);
        assertTrue(service.clearSessions(recoveryEnvelope(
                        "recovery-audit-recorded", "recovery-audit-recorded-operation", snapshot), admin,
                ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED).accepted());
        assertEquals(2, recoveryAuditAttempts.get());
    }

    @Test
    void sameRecoveryOperationCannotRaceItsAuditOrMutationBeforeReplayIsStored() throws Exception {
        MutableClock clock = new MutableClock();
        CountDownLatch auditEntered = new CountDownLatch(1);
        CountDownLatch releaseAudit = new CountDownLatch(1);
        AtomicInteger mutationAuditAttempts = new AtomicInteger();
        AuditPort audit = event -> {
            if (event.operationId() != null) {
                mutationAuditAttempts.incrementAndGet();
                auditEntered.countDown();
                try {
                    assertTrue(releaseAudit.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            return AuditDisposition.RECORDED;
        };
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), audit, clock,
                Duration.ofMinutes(5), Duration.ofNanos(1));
        AuthenticatedActor admin = actor(ActorId.of("recovery-in-flight"), 4, "en-US", false);
        DiagnosticsSnapshot snapshot = service.open(new DiagnosticsOpenRequest(
                RequestId.of("recovery-in-flight-open")), admin, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        clock.advance(Duration.ofNanos(1));
        FoundationMutationEnvelope envelope = recoveryEnvelope(
                "recovery-in-flight-request", "recovery-in-flight-operation", snapshot);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DiagnosticsRecoveryResult> first = executor.submit(() -> service.clearSessions(
                    envelope, admin, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED));
            assertTrue(auditEntered.await(2, TimeUnit.SECONDS));
            DiagnosticsRecoveryResult inFlightRetry = service.clearSessions(
                    envelope, admin, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED);
            assertEquals(FoundationErrorCode.RATE_LIMITED,
                    inFlightRetry.error().orElseThrow().code());
            assertEquals(1, mutationAuditAttempts.get(), "second request cannot enter audit");

            releaseAudit.countDown();
            assertTrue(first.get(2, TimeUnit.SECONDS).accepted());
            clock.advance(Duration.ofNanos(1));
            assertTrue(service.clearSessions(envelope, admin, ACCEPTED,
                    ConnectionState.HANDSHAKE_ACCEPTED).accepted(),
                    "stored result replays after the first callback completes");
            assertEquals(1, mutationAuditAttempts.get(), "replay cannot re-audit or re-mutate");
            assertEquals(0, service.sessionCount().count());
        } finally {
            releaseAudit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void recoveryCommitInvalidatesAnInFlightRefreshWithoutResurrection() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicBoolean blockRefresh = new AtomicBoolean(false);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(ServiceKey.of("recovery-race", HealthAwareService.class), () -> {
            if (blockRefresh.get()) {
                entered.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            return ServiceHealth.healthy("recovery-race");
        }, "recovery-race-owner");
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(registry), event -> AuditDisposition.RECORDED, clock,
                Duration.ofMinutes(5), Duration.ofNanos(1));
        AuthenticatedActor admin = actor(ActorId.of("race-admin"), 4, "en-US", false);
        AuthenticatedActor victim = actor(ActorId.of("race-victim"), 0, "en-US", false);
        DiagnosticsSnapshot adminSnapshot = service.open(new DiagnosticsOpenRequest(
                RequestId.of("race-admin-open")), admin, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        clock.advance(Duration.ofNanos(1));
        DiagnosticsSnapshot victimSnapshot = service.open(new DiagnosticsOpenRequest(
                RequestId.of("race-victim-open")), victim, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        clock.advance(Duration.ofNanos(1));
        blockRefresh.set(true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DiagnosticsRefreshResult> refresh = executor.submit(() -> service.refresh(
                    new DiagnosticsRefreshRequest(RequestId.of("race-refresh"), victimSnapshot.sessionId(),
                            victimSnapshot.revision()), victim, ACCEPTED,
                    ConnectionState.HANDSHAKE_ACCEPTED));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(service.clearSessions(recoveryEnvelope(
                            "race-clear", "race-clear-operation", adminSnapshot), admin, ACCEPTED,
                    ConnectionState.HANDSHAKE_ACCEPTED).accepted());
            release.countDown();
            assertEquals(FoundationErrorCode.INVALID_SESSION,
                    refresh.get(2, TimeUnit.SECONDS).error().orElseThrow().code());
            assertEquals(0, service.sessionCount().count());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void recoveryRejectsWrongActorSessionVersionAndHandshakeBeforeAnyMutation() {
        MutableClock clock = new MutableClock();
        DiagnosticsApplicationService service = new DiagnosticsApplicationService(
                new FoundationDiagnosticsQuery(new ServiceRegistry()), event -> AuditDisposition.RECORDED,
                clock, Duration.ofMinutes(5), Duration.ofNanos(1));
        AuthenticatedActor owner = actor(ActorId.of("validation-owner"), 4, "en-US", false);
        AuthenticatedActor other = actor(ActorId.of("validation-other"), 4, "en-US", false);
        DiagnosticsSnapshot snapshot = service.open(new DiagnosticsOpenRequest(
                RequestId.of("validation-open")), owner, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        FoundationMutationEnvelope correct = recoveryEnvelope(
                "validation-request", "validation-operation", snapshot);

        clock.advance(Duration.ofNanos(1));
        assertEquals(FoundationErrorCode.INVALID_SESSION, service.clearSessions(correct, other, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        clock.advance(Duration.ofNanos(1));
        assertEquals(FoundationErrorCode.INVALID_SESSION, service.clearSessions(FoundationMutationEnvelope.of(
                        RequestId.of("missing-session-request"), OperationId.of("missing-session-operation"),
                        SessionId.of("missing-session"), snapshot.revision()), owner, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        clock.advance(Duration.ofNanos(1));
        assertEquals(FoundationErrorCode.REVISION_CONFLICT, service.clearSessions(
                FoundationMutationEnvelope.of(RequestId.of("wrong-version-request"),
                        OperationId.of("wrong-version-operation"), snapshot.sessionId(),
                        Revision.of(snapshot.revision().value() + 1)), owner, ACCEPTED,
                ConnectionState.HANDSHAKE_ACCEPTED).error().orElseThrow().code());
        clock.advance(Duration.ofNanos(1));
        assertEquals(FoundationErrorCode.HANDSHAKE_REQUIRED, service.clearSessions(correct, owner,
                HandshakeDecision.rejected(HandshakeRejectReason.MALFORMED_HANDSHAKE),
                ConnectionState.REJECTED).error().orElseThrow().code());
        assertEquals(1, service.sessionCount().count());
    }

    private static AuthenticatedActor actor(
            ActorId id, int permission, String locale, boolean streamerMode) {
        return AuthenticatedActor.of(id, permission, LocaleTag.of(locale), streamerMode);
    }

    private static FoundationMutationEnvelope recoveryEnvelope(
            String requestId, String operationId, DiagnosticsSnapshot snapshot) {
        return FoundationMutationEnvelope.of(RequestId.of(requestId), OperationId.of(operationId),
                snapshot.sessionId(), snapshot.revision());
    }

    private static void assertUnchangedAfter(
            DiagnosticsSnapshot current,
            DiagnosticsApplicationService service,
            AuthenticatedActor actor,
            MutableClock clock,
            String requestPrefix) {
        clock.advance(Duration.ofNanos(1));
        DiagnosticsRefreshResult result = service.refresh(
                new DiagnosticsRefreshRequest(RequestId.of(requestPrefix),
                        current.sessionId(), current.revision()),
                actor, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED);
        assertEquals(DiagnosticsRefreshResult.Kind.UNCHANGED, result.kind());
        assertTrue(result.delta().isEmpty());
    }

    private static final class MutableClock extends Clock {
        private volatile Instant current = Instant.parse("2026-01-01T00:00:00Z");
        private final AtomicBoolean failNextRead = new AtomicBoolean(false);

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        void failNextRead() {
            failNextRead.set(true);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (failNextRead.compareAndSet(true, false)) {
                throw new IllegalStateException("clock failure");
            }
            return current;
        }
    }
}
