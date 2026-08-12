package io.github.yu1sh.reality.foundation.forge;

import com.mojang.brigadier.CommandDispatcher;
import io.github.yu1sh.reality.foundation.api.DiagnosticsCountResult;
import io.github.yu1sh.reality.foundation.api.DiagnosticsRecoveryResult;
import io.github.yu1sh.reality.foundation.api.DiagnosticsStatusResult;
import io.github.yu1sh.reality.foundation.api.FoundationError;
import io.github.yu1sh.reality.foundation.api.FoundationErrorCode;
import io.github.yu1sh.reality.foundation.api.FoundationMutationEnvelope;
import io.github.yu1sh.reality.foundation.api.FoundationVersion;
import io.github.yu1sh.reality.foundation.api.HandshakeDecision;
import io.github.yu1sh.reality.foundation.api.ConnectionState;
import io.github.yu1sh.reality.identity.ActorId;
import io.github.yu1sh.reality.identity.OperationId;
import io.github.yu1sh.reality.identity.RequestId;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.UUID;

/** Forge lifecycle, command, and player cleanup adapter. */
public final class FoundationForgeEvents {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String EVIDENCE_RUN_ID_PROPERTY = "reality.foundation.evidence.run-id";
    private final FoundationRuntime runtime;

    public FoundationForgeEvents(FoundationRuntime runtime) {
        this.runtime = runtime;
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        runtime.serverStarting(event.getServer());
        String runId = System.getProperty(EVIDENCE_RUN_ID_PROPERTY, "");
        if (runId.matches("[a-z0-9][a-z0-9-]{15,127}")) {
            LOGGER.info("foundation.evidence.run_id={} mod_id={} server_started",
                    runId, FoundationVersion.MOD_ID);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        runtime.serverStopping(event.getServer());
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            runtime.playerLoggedOut(player);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("realityfoundation")
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(Commands.literal("recovery")
                        .then(Commands.literal("clear-sessions")
                                .executes(context -> clearSessions(context.getSource())))));
    }

    private int status(CommandSourceStack source) {
        FoundationServerState state = runtime.state(source.getServer()).orElse(null);
        if (state == null) {
            source.sendFailure(Component.translatable("foundation.error.internal_failure"));
            return 0;
        }
        AuthenticatedCommandActor actor = AuthenticatedCommandActor.from(
                source, state.preferencePort());
        DiagnosticsStatusResult result = state.context().diagnostics().status(
                commandRequestId("status"), actor.actor(),
                HandshakeDecision.acceptedDecision(), ConnectionState.HANDSHAKE_ACCEPTED);
        if (!result.accepted()) {
            source.sendFailure(Component.translatable(result.error().orElse(
                    FoundationError.of(FoundationErrorCode.INTERNAL_FAILURE)).messageKey()));
            return 0;
        }
        result.snapshot().publicValues().forEach((key, value) ->
                source.sendSuccess(() -> Component.translatable(
                        "foundation.command.status.line", key, value), false));
        if (actor.actor().permissionLevel() >= 2 && !actor.actor().streamerMode()) {
            result.snapshot().adminValues().forEach((key, value) ->
                    source.sendSuccess(() -> Component.translatable(
                            "foundation.command.admin.line", key, value), false));
        }
        return 1;
    }

    private int clearSessions(CommandSourceStack source) {
        FoundationServerState state = runtime.state(source.getServer()).orElse(null);
        if (state == null) {
            source.sendFailure(Component.translatable("foundation.error.internal_failure"));
            return 0;
        }
        AuthenticatedCommandActor actor = AuthenticatedCommandActor.from(
                source, state.preferencePort());
        RequestId requestId = commandRequestId("recovery");
        FoundationMutationEnvelope envelope = state.context().diagnostics()
                .recoveryEnvelopeForActiveSession(requestId,
                        OperationId.of("command-recovery-" + UUID.randomUUID()), actor.actor());
        if (envelope == null) {
            source.sendFailure(Component.translatable("foundation.error.invalid_session"));
            return 0;
        }
        // The command adapter uses the identical server-side application
        // mutation path as a future GUI action. Both actor and permission are
        // projected from CommandSourceStack/ServerPlayer, never from packet
        // fields or command arguments.
        DiagnosticsRecoveryResult result = state.context().diagnostics().clearSessions(
                envelope, actor.actor(), HandshakeDecision.acceptedDecision(),
                ConnectionState.HANDSHAKE_ACCEPTED);
        if (!result.accepted()) {
            source.sendFailure(Component.translatable(result.error().orElse(
                    FoundationError.of(FoundationErrorCode.INTERNAL_FAILURE)).messageKey()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "foundation.command.recovery.cleared", result.auditDisposition().name().toLowerCase()), true);
        return 1;
    }

    private static RequestId commandRequestId(String action) {
        return RequestId.of("command-" + action + "-" + UUID.randomUUID());
    }

    private record AuthenticatedCommandActor(io.github.yu1sh.reality.foundation.api.AuthenticatedActor actor) {
        static AuthenticatedCommandActor from(
                CommandSourceStack source, FoundationServerPreferencePort preferencePort) {
            if (source.getEntity() instanceof ServerPlayer player) {
                return new AuthenticatedCommandActor(ForgeActors.from(player, preferencePort));
            }
            int permission = source.hasPermission(4) ? 4 : source.hasPermission(2) ? 2 : 0;
            return new AuthenticatedCommandActor(
                    io.github.yu1sh.reality.foundation.api.AuthenticatedActor.of(
                            ActorId.of("console"), permission,
                            preferencePort.consoleLocale(),
                            preferencePort.streamerModeForConsole()));
        }
    }
}
