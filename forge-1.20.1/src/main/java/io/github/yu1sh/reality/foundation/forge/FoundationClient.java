package io.github.yu1sh.reality.foundation.forge;

import io.github.yu1sh.reality.foundation.api.ClientHelloPacket;
import io.github.yu1sh.reality.foundation.api.DiagnosticsDeltaPacket;
import io.github.yu1sh.reality.foundation.api.DiagnosticsErrorPacket;
import io.github.yu1sh.reality.foundation.api.DiagnosticsRecoveryResultPacket;
import io.github.yu1sh.reality.foundation.api.DiagnosticsSnapshotPacket;
import io.github.yu1sh.reality.foundation.api.FoundationHandshake;
import io.github.yu1sh.reality.foundation.api.FoundationPacket;
import io.github.yu1sh.reality.foundation.api.OpenDiagnosticsPacket;
import io.github.yu1sh.reality.identity.RequestId;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;

import java.util.UUID;

/** Client-only keybind, native screen registration, and packet application. */
public final class FoundationClient {
    private static FoundationClient instance;
    private static KeyMapping openStatusKey;
    private static boolean handshakeAccepted;

    private FoundationClient() {
    }

    public static void register(IEventBus modBus, FoundationRuntime runtime) {
        instance = new FoundationClient();
        FoundationNetwork.registerClientPacketHandler(FoundationClient::receiveServerPacket);
        modBus.addListener(FoundationClient::clientSetup);
        modBus.addListener(FoundationClient::registerKeyMappings);
        MinecraftForgeClientEvents.register(instance);
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(
                FoundationMenus.DIAGNOSTICS.get(), DiagnosticsScreen::new));
    }

    private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        openStatusKey = new KeyMapping(
                "key.reality_foundation.open_status",
                KeyConflictContext.IN_GAME,
                KeyModifier.NONE,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                "key.categories.reality_foundation");
        event.register(openStatusKey);
    }

    @SubscribeEvent
    private void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        handshakeAccepted = false;
        FoundationNetwork.sendToServer(new ClientHelloPacket(FoundationHandshake.current()));
    }

    @SubscribeEvent
    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || openStatusKey == null
                || !openStatusKey.consumeClick() || !handshakeAccepted) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            FoundationNetwork.sendToServer(new OpenDiagnosticsPacket(
                    RequestId.of("open-" + UUID.randomUUID())));
        }
    }

    static void receiveHandshake(io.github.yu1sh.reality.foundation.api.HandshakeResultPacket packet) {
        handshakeAccepted = packet.accepted();
        if (!packet.accepted() && Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(
                    Component.translatable("foundation.error." + packet.reason()), false);
        }
    }

    private static void receiveServerPacket(FoundationPacket packet) {
        if (packet instanceof io.github.yu1sh.reality.foundation.api.HandshakeResultPacket handshake) {
            receiveHandshake(handshake);
        } else if (packet instanceof DiagnosticsSnapshotPacket snapshot) {
            receiveSnapshot(snapshot);
        } else if (packet instanceof DiagnosticsDeltaPacket delta) {
            receiveDelta(delta);
        } else if (packet instanceof DiagnosticsErrorPacket error) {
            receiveError(error);
        } else if (packet instanceof DiagnosticsRecoveryResultPacket recovery) {
            receiveRecoveryResult(recovery);
        }
    }

    static void receiveSnapshot(DiagnosticsSnapshotPacket packet) {
        if (Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.containerMenu instanceof DiagnosticsMenu menu) {
            try {
                menu.applySnapshot(packet.snapshot());
                menu.setErrorMessageKey(null);
            } catch (RuntimeException failure) {
                menu.setErrorMessageKey("foundation.error.malformed_request");
            }
        }
    }

    static void receiveDelta(DiagnosticsDeltaPacket packet) {
        if (Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.containerMenu instanceof DiagnosticsMenu menu) {
            try {
                menu.applyDelta(packet.delta());
                menu.setErrorMessageKey(null);
            } catch (RuntimeException failure) {
                menu.setErrorMessageKey("foundation.error.revision_conflict");
            }
        }
    }

    static void receiveError(DiagnosticsErrorPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        String messageKey = packet.error().messageKey();
        if (minecraft.screen instanceof DiagnosticsScreen
                && player.containerMenu instanceof DiagnosticsMenu menu) {
            menu.setErrorMessageKey(messageKey);
        } else {
            player.displayClientMessage(Component.translatable(
                    "foundation.gui.error", Component.translatable(messageKey)), false);
        }
    }

    static void receiveRecoveryResult(DiagnosticsRecoveryResultPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player != null && player.containerMenu instanceof DiagnosticsMenu menu) {
            menu.applyRecoveryResult(packet.requestId(), packet.result());
        }
    }

    private static final class MinecraftForgeClientEvents {
        private MinecraftForgeClientEvents() {
        }

        private static void register(FoundationClient client) {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(client);
        }
    }
}
