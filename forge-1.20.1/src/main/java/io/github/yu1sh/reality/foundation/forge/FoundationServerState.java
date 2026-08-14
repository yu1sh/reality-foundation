package io.github.yu1sh.reality.foundation.forge;

import io.github.yu1sh.reality.foundation.api.ClientHelloPacket;
import io.github.yu1sh.reality.foundation.api.ClearDiagnosticsSessionsPacket;
import io.github.yu1sh.reality.foundation.api.ConnectionState;
import io.github.yu1sh.reality.foundation.api.DiagnosticsApplicationService;
import io.github.yu1sh.reality.foundation.api.DiagnosticsErrorPacket;
import io.github.yu1sh.reality.foundation.api.DiagnosticsOpenRequest;
import io.github.yu1sh.reality.foundation.api.DiagnosticsOpenResult;
import io.github.yu1sh.reality.foundation.api.DiagnosticsRefreshRequest;
import io.github.yu1sh.reality.foundation.api.DiagnosticsRefreshResult;
import io.github.yu1sh.reality.foundation.api.DiagnosticsRecoveryResult;
import io.github.yu1sh.reality.foundation.api.DiagnosticsRecoveryResultPacket;
import io.github.yu1sh.reality.foundation.api.FoundationError;
import io.github.yu1sh.reality.foundation.api.FoundationErrorCode;
import io.github.yu1sh.reality.foundation.api.FoundationHandshake;
import io.github.yu1sh.reality.foundation.api.HandshakeDecision;
import io.github.yu1sh.reality.foundation.api.HandshakeRejectReason;
import io.github.yu1sh.reality.foundation.api.HandshakeValidator;
import io.github.yu1sh.reality.foundation.api.RealityServerContext;
import io.github.yu1sh.reality.foundation.api.DiagnosticsSnapshotPacket;
import io.github.yu1sh.reality.foundation.api.DiagnosticsDeltaPacket;
import io.github.yu1sh.reality.foundation.api.RefreshDiagnosticsPacket;
import io.github.yu1sh.reality.foundation.api.OpenDiagnosticsPacket;
import io.github.yu1sh.reality.identity.RequestId;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-server handshake/session adapter around the Minecraft-independent API. */
public final class FoundationServerState implements AutoCloseable {
    private final RealityServerContext context;
    private final FoundationServerPreferencePort preferencePort;
    private final HandshakeValidator handshakeValidator =
            new HandshakeValidator(FoundationHandshake.current());
    private final Map<UUID, HandshakeDecision> playerHandshakes = new HashMap<>();

    FoundationServerState(
            RealityServerContext context, FoundationServerPreferencePort preferencePort) {
        this.context = context;
        this.preferencePort = java.util.Objects.requireNonNull(preferencePort, "preferencePort");
    }

    public RealityServerContext context() {
        return context;
    }

    FoundationServerPreferencePort preferencePort() {
        return preferencePort;
    }

    io.github.yu1sh.reality.foundation.api.AuthenticatedActor actorFor(ServerPlayer player) {
        return ForgeActors.from(player, preferencePort);
    }

    public void handleHello(ServerPlayer player, ClientHelloPacket packet) {
        HandshakeDecision decision = handshakeValidator.evaluate(packet.handshake());
        synchronized (playerHandshakes) {
            playerHandshakes.put(player.getUUID(), decision);
        }
        FoundationNetwork.sendToPlayer(
                new io.github.yu1sh.reality.foundation.api.HandshakeResultPacket(
                        decision.accepted(), decision.stableReason()), player);
        if (!decision.accepted()) {
            player.connection.disconnect(Component.translatable(
                    decision.rejection().map(HandshakeRejectReason::messageKey)
                            .orElse("foundation.error.malformed_handshake")));
        }
    }

    public void handleOpen(ServerPlayer player, OpenDiagnosticsPacket packet) {
        HandshakeDecision decision = decisionFor(player);
        io.github.yu1sh.reality.foundation.api.AuthenticatedActor actor = actorFor(player);
        DiagnosticsOpenResult result = context.diagnostics().open(
                new DiagnosticsOpenRequest(packet.requestId()),
                actor, decision, connectionState(decision));
        if (!result.accepted()) {
            sendError(player, packet.requestId(), result.error().orElse(
                    FoundationError.of(FoundationErrorCode.INTERNAL_FAILURE)));
            return;
        }
        try {
            // Forge may reject a menu open (or a mock/test connection may
            // throw) after the API has reserved a session. Do not publish the
            // snapshot until the server confirms that a menu was created.
            var opened = player.openMenu(new DiagnosticsMenuProvider(
                    result.snapshot(), context.diagnostics(), actor.actorId()));
            if (opened.isEmpty()) {
                invalidateFailedOpen(actor.actorId(), result.snapshot().sessionId());
                sendError(player, packet.requestId(),
                        FoundationError.of(FoundationErrorCode.INTERNAL_FAILURE));
                return;
            }
            FoundationNetwork.sendToPlayer(
                    new DiagnosticsSnapshotPacket(result.snapshot()), player);
        } catch (RuntimeException failure) {
            invalidateFailedOpen(actor.actorId(), result.snapshot().sessionId());
            try {
                sendError(player, packet.requestId(),
                        FoundationError.of(FoundationErrorCode.INTERNAL_FAILURE));
            } catch (RuntimeException ignored) {
                // A broken transport must not turn a failed open into a
                // ghost session or an unchecked lifecycle error.
            }
        }
    }

    public void handleRefresh(ServerPlayer player, RefreshDiagnosticsPacket packet) {
        HandshakeDecision decision = decisionFor(player);
        DiagnosticsRefreshResult result = context.diagnostics().refresh(
                new DiagnosticsRefreshRequest(
                        packet.requestId(), packet.sessionId(), packet.expectedRevision()),
                actorFor(player), decision, connectionState(decision));
        if (result.kind() == DiagnosticsRefreshResult.Kind.DELTA) {
            FoundationNetwork.sendToPlayer(
                    new DiagnosticsDeltaPacket(result.delta().orElseThrow()), player);
        } else if (result.kind() == DiagnosticsRefreshResult.Kind.DENIED) {
            sendError(player, packet.requestId(), result.error().orElse(
                    FoundationError.of(FoundationErrorCode.INTERNAL_FAILURE)));
        }
    }

    /**
     * Rebuilds all authority inputs from the active server and sender. The
     * packet envelope is only an untrusted correlation/version proposal; the
     * application service performs the session, permission, replay, and audit
     * checks shared with the command adapter.
     */
    public void handleRecovery(ServerPlayer player, ClearDiagnosticsSessionsPacket packet) {
        HandshakeDecision decision = decisionFor(player);
        DiagnosticsRecoveryResult result = context.diagnostics().clearSessions(
                packet.envelope(), actorFor(player), decision, connectionState(decision));
        FoundationNetwork.sendToPlayer(new DiagnosticsRecoveryResultPacket(
                packet.envelope().requestId(), result), player);
    }

    public void removePlayer(ServerPlayer player) {
        synchronized (playerHandshakes) {
            playerHandshakes.remove(player.getUUID());
        }
        try {
            context.diagnostics().invalidateActorSession(actorFor(player).actorId());
        } catch (IllegalStateException ignored) {
            // Logout can race server stopping. Context close is the terminal
            // invalidation boundary and must not leak an unchecked lifecycle
            // exception into Forge's logout event.
        }
    }

    public void sendError(ServerPlayer player, RequestId requestId,
                          FoundationError error) {
        FoundationNetwork.sendToPlayer(new DiagnosticsErrorPacket(requestId, error), player);
    }

    private void invalidateFailedOpen(
            io.github.yu1sh.reality.identity.ActorId actorId,
            io.github.yu1sh.reality.identity.SessionId sessionId) {
        try {
            context.diagnostics().invalidateSession(actorId, sessionId);
        } catch (RuntimeException ignored) {
            // Context close is the terminal cleanup boundary. There is no
            // session to expose if invalidation races that close.
        }
    }

    private HandshakeDecision decisionFor(ServerPlayer player) {
        synchronized (playerHandshakes) {
            return playerHandshakes.getOrDefault(player.getUUID(),
                    HandshakeDecision.rejected(HandshakeRejectReason.MALFORMED_HANDSHAKE));
        }
    }

    private static ConnectionState connectionState(HandshakeDecision decision) {
        return decision.accepted() ? ConnectionState.HANDSHAKE_ACCEPTED : ConnectionState.REJECTED;
    }

    @Override
    public void close() {
        synchronized (playerHandshakes) {
            playerHandshakes.clear();
        }
        context.close();
    }
}
