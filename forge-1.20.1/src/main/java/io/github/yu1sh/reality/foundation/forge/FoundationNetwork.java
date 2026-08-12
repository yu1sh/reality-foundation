package io.github.yu1sh.reality.foundation.forge;

import io.github.yu1sh.reality.foundation.api.ClientHelloPacket;
import io.github.yu1sh.reality.foundation.api.FoundationPacket;
import io.github.yu1sh.reality.foundation.api.FoundationPacketCodec;
import io.github.yu1sh.reality.foundation.api.FoundationVersion;
import io.github.yu1sh.reality.foundation.api.OpenDiagnosticsPacket;
import io.github.yu1sh.reality.foundation.api.RefreshDiagnosticsPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Single bounded Forge transport adapter around the API codec. */
public final class FoundationNetwork {
    private static final String PROTOCOL = Integer.toString(FoundationVersion.NETWORK_PROTOCOL);
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(FoundationVersion.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);
    private static boolean registered;
    private static final MalformedPacketAbuseGuard MALFORMED_PACKET_GUARD =
            new MalformedPacketAbuseGuard();
    // Registered by the Dist.CLIENT-only class at client setup. Keeping only
    // this common Consumer reference means dedicated-server bytecode has no
    // symbolic linkage to FoundationClient or any net.minecraft.client type.
    private static volatile Consumer<FoundationPacket> clientPacketHandler;

    private FoundationNetwork() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        CHANNEL.registerMessage(0, FoundationForgePacket.class,
                FoundationForgePacket::encode,
                FoundationForgePacket::decode,
                FoundationForgePacket::handle);
        registered = true;
    }

    public static void registerClientPacketHandler(Consumer<FoundationPacket> handler) {
        clientPacketHandler = Objects.requireNonNull(handler, "handler");
    }

    public static void sendToServer(FoundationPacket packet) {
        CHANNEL.sendToServer(new FoundationForgePacket(FoundationPacketCodec.encode(packet)));
    }

    public static void sendToPlayer(FoundationPacket packet, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new FoundationForgePacket(FoundationPacketCodec.encode(packet)));
    }

    private static final class FoundationForgePacket {
        private final byte[] payload;
        // Transport decoding happens before Forge supplies the sender to this
        // class. Preserve an invalid length as a bounded marker so dispatch
        // can apply the sender-keyed boundary guard instead of throwing out
        // of the codec path before any abuse accounting is possible.
        private final boolean malformedTransport;

        private FoundationForgePacket(byte[] payload) {
            this(payload, false);
        }

        private FoundationForgePacket(byte[] payload, boolean malformedTransport) {
            if (malformedTransport) {
                this.payload = new byte[0];
                this.malformedTransport = true;
                return;
            }
            if (payload == null || payload.length == 0
                    || payload.length > FoundationPacketCodec.MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("malformed_packet");
            }
            this.payload = payload.clone();
            this.malformedTransport = false;
        }

        private static void encode(FoundationForgePacket packet, FriendlyByteBuf buffer) {
            buffer.writeBytes(packet.payload);
        }

        private static FoundationForgePacket decode(FriendlyByteBuf buffer) {
            int length = buffer.readableBytes();
            if (length <= 0 || length > FoundationPacketCodec.MAX_PACKET_BYTES) {
                return new FoundationForgePacket(null, true);
            }
            byte[] payload = new byte[length];
            buffer.readBytes(payload);
            return new FoundationForgePacket(payload);
        }

        private static void handle(FoundationForgePacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> dispatch(packet, context));
            context.setPacketHandled(true);
        }

        private static void dispatch(FoundationForgePacket packet, NetworkEvent.Context context) {
            if (packet.malformedTransport) {
                rejectMalformed(context);
                return;
            }
            FoundationPacket decoded;
            try {
                decoded = FoundationPacketCodec.decode(packet.payload);
            } catch (RuntimeException failure) {
                rejectMalformed(context);
                return;
            }

            ServerPlayer sender = context.getSender();
            if (sender != null) {
                FoundationRuntime runtime = RealityFoundationMod.instance().runtime();
                FoundationServerState state = runtime.state(sender).orElse(null);
                if (state == null) {
                    sender.connection.disconnect(Component.translatable(
                            "foundation.error.internal_failure"));
                    return;
                }
                if (decoded instanceof ClientHelloPacket hello) {
                    state.handleHello(sender, hello);
                } else if (decoded instanceof OpenDiagnosticsPacket open) {
                    state.handleOpen(sender, open);
                } else if (decoded instanceof RefreshDiagnosticsPacket refresh) {
                    state.handleRefresh(sender, refresh);
                } else {
                    rejectMalformed(context);
                }
                return;
            }

            Consumer<FoundationPacket> handler = clientPacketHandler;
            if (handler != null) {
                handler.accept(decoded);
            }
        }

        private static void rejectMalformed(NetworkEvent.Context context) {
            ServerPlayer sender = context.getSender();
            if (sender != null && MALFORMED_PACKET_GUARD.shouldDisconnect(
                    sender.getUUID(), System.nanoTime())) {
                sender.connection.disconnect(Component.translatable(
                        "foundation.error.malformed_request"));
            }
        }
    }
}
