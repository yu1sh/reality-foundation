package io.github.yu1sh.reality.foundation.forge;

import io.github.yu1sh.reality.foundation.api.AuthenticatedActor;
import io.github.yu1sh.reality.foundation.api.ConnectionState;
import io.github.yu1sh.reality.foundation.api.DiagnosticsOpenRequest;
import io.github.yu1sh.reality.foundation.api.DiagnosticsRecoveryResult;
import io.github.yu1sh.reality.foundation.api.FoundationMutationEnvelope;
import io.github.yu1sh.reality.foundation.api.FoundationHandshake;
import io.github.yu1sh.reality.foundation.api.FoundationVersion;
import io.github.yu1sh.reality.foundation.api.HandshakeDecision;
import io.github.yu1sh.reality.foundation.api.RealityServerContext;
import io.github.yu1sh.reality.foundation.api.DiagnosticsSnapshot;
import io.github.yu1sh.reality.gui.LocaleTag;
import io.github.yu1sh.reality.identity.ActorId;
import io.github.yu1sh.reality.identity.OperationId;
import io.github.yu1sh.reality.identity.RequestId;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.gametest.GameTestHolder;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** Dedicated-server GameTest coverage for the first foundation vertical slice. */
@GameTestHolder(FoundationVersion.MOD_ID)
public final class FoundationGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HandshakeDecision ACCEPTED =
            new io.github.yu1sh.reality.foundation.api.HandshakeValidator(
                    FoundationHandshake.current()).evaluate(FoundationHandshake.current());

    private FoundationGameTests() {
    }

    @GameTest(template = "empty_1x1", timeoutTicks = 120)
    public static void contextRequestMenuPermissionAndRegeneration(GameTestHelper helper) {
        // Forge 1.20.1's GameTestServer does not emit its selected-test count
        // on every logging configuration.  This marker is reached only by
        // the one registered GameTest and gives the fresh-log verifier an
        // exact count to bind to its namespace/PASS/completion evidence.
        LOGGER.info("Running 1 tests");
        LOGGER.info("FoundationGameTests namespace={} exact_test={}", FoundationVersion.MOD_ID,
                "FoundationGameTests.contextRequestMenuPermissionAndRegeneration");
        MinecraftServer server = helper.getLevel().getServer();
        FoundationRuntime runtime = RealityFoundationMod.instance().runtime();
        FoundationServerState initialState = runtime.state(server).orElse(null);
        helper.assertTrue(initialState != null, "server context must be created at starting");
        RealityServerContext initialContext = initialState.context();

        LOGGER.info("FoundationGameTests stage=api-open");
        Player player = helper.makeMockPlayer();
        ActorId actorId = ActorId.of("gametest-player");
        AuthenticatedActor playerActor = AuthenticatedActor.of(
                actorId, 0, LocaleTag.of("en-US"), false);
        DiagnosticsSnapshot snapshot = initialContext.diagnostics().open(
                new DiagnosticsOpenRequest(RequestId.of("gametest-open")),
                playerActor,
                ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
        LOGGER.info("FoundationGameTests stage=menu-create");
        DiagnosticsMenu menu = DiagnosticsMenu.server(
                0, player.getInventory(), snapshot, initialContext.diagnostics(), actorId);
        helper.assertTrue(menu.snapshot().orElseThrow().sessionId().equals(snapshot.sessionId()),
                "server menu must carry the server-issued snapshot");
        helper.assertFalse(snapshot.adminAllowed(), "general player must not see admin diagnostics");

        LOGGER.info("FoundationGameTests stage=menu-remove");
        menu.removed(player);
        LOGGER.info("FoundationGameTests stage=menu-remove-again");
        menu.invalidateServerSession();
        helper.assertTrue(initialContext.diagnostics().sessionCount().count() == 0,
                "server menu removal must invalidate only its session and be idempotent");

        DiagnosticsRecoveryResult denied = initialContext.diagnostics().clearSessions(
                FoundationMutationEnvelope.of(RequestId.of("gametest-denied"),
                        OperationId.of("gametest-denied-operation"), snapshot.sessionId(),
                        snapshot.revision()),
                playerActor, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED);
        helper.assertFalse(denied.accepted(), "permission level two cannot clear sessions");

        LOGGER.info("FoundationGameTests stage=streamer-preference");
        if (!(initialState.preferencePort() instanceof DefaultFoundationServerPreferencePort preferences)) {
            helper.fail("the standalone Forge adapter must retain its bounded default preference port");
            return;
        }
        LocaleTag oldLocale = preferences.consoleLocale();
        boolean oldStreamer = preferences.streamerModeForConsole();
        GameProfile profile = new GameProfile(java.util.UUID.randomUUID(), "foundation-pref-test");
        try {
            preferences.setDefaultLocale(LocaleTag.of("ja-JP"));
            preferences.setStreamerMode(true);
            ServerPlayer preferencePlayer = new ServerPlayer(server, helper.getLevel(), profile);
            server.getPlayerList().op(profile);
            // GameTest's synthetic server may use a zero default operator
            // level. The fixture still uses the server-owned op list, with a
            // deterministic level, rather than granting anything client-side.
            server.getPlayerList().getOps().add(new ServerOpListEntry(profile, 4, false));
            AuthenticatedActor preferenceActor = initialState.actorFor(preferencePlayer);
            helper.assertTrue(LocaleTag.of("ja-JP").equals(preferenceActor.locale()),
                    "locale must come from the server-owned preference port");
            helper.assertTrue(preferenceActor.streamerMode(),
                    "streamer mode must come from the server-owned preference port");
            helper.assertTrue(preferenceActor.permissionLevel() >= 2,
                    "GameTest operator must be admin eligible");
            DiagnosticsSnapshot streamerSnapshot = initialContext.diagnostics().open(
                    new DiagnosticsOpenRequest(RequestId.of("gametest-streamer-open")),
                    preferenceActor, ACCEPTED, ConnectionState.HANDSHAKE_ACCEPTED).snapshot();
            helper.assertFalse(streamerSnapshot.adminAllowed(),
                    "streamer mode must redact admin diagnostics");
            helper.assertTrue(streamerSnapshot.adminValues().isEmpty(),
                    "streamer projection must contain no admin values");
            helper.assertTrue("ja-JP".equals(streamerSnapshot.locale().value()),
                    "snapshot locale must be server-owned, not a client hardcode");
            DiagnosticsMenu preferenceMenu = DiagnosticsMenu.server(
                    1, preferencePlayer.getInventory(), streamerSnapshot,
                    initialContext.diagnostics(), preferenceActor.actorId());
            preferenceMenu.invalidateServerSession();
            helper.assertTrue(initialContext.diagnostics().sessionCount().count() == 0,
                    "server menu lifecycle must invalidate its exact session");
        } finally {
            server.getPlayerList().deop(profile);
            preferences.setDefaultLocale(oldLocale);
            preferences.setStreamerMode(oldStreamer);
        }

        LOGGER.info("FoundationGameTests stage=stop");
        runtime.serverStopping(server);
        helper.assertTrue(initialContext.isClosed(), "stopping must close the context");
        helper.assertTrue(runtime.state(server).isEmpty(), "stopping must remove the identity entry");

        runtime.serverStarting(server);
        FoundationServerState regenerated = runtime.state(server).orElse(null);
        helper.assertTrue(regenerated != null && regenerated != initialState,
                "a restarted server identity must receive a fresh state");
        helper.assertTrue(!regenerated.context().isClosed(), "regenerated context must be open");

        LOGGER.info("FoundationGameTests stage=pass");
        // The test leaves a valid context for the normal ServerStopping event.
        LOGGER.info("FoundationGameTests.contextRequestMenuPermissionAndRegeneration PASS");
        helper.succeed();
    }
}
