package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.gui.LocaleTag;
import io.github.yu1sh.reality.identity.RequestId;
import io.github.yu1sh.reality.identity.SessionId;
import io.github.yu1sh.reality.version.Revision;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic, Minecraft-independent packet codec used by the Forge
 * FriendlyByteBuf adapter. All counts, lengths, discriminators, and revisions
 * are validated before an object is constructed.
 */
public final class FoundationPacketCodec {
    public static final int MAX_PACKET_BYTES = 32 * 1024;
    public static final int MAX_STRING_BYTES = 512;
    public static final int MAX_MAP_ENTRIES = DiagnosticsSnapshot.MAX_FIELDS;

    private FoundationPacketCodec() {
    }

    public static byte[] encode(FoundationPacket packet) {
        if (packet == null) {
            throw new PacketCodecException("malformed_packet");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeByte(packet.discriminator());
            if (packet instanceof ClientHelloPacket hello) {
                writeHandshake(output, hello.handshake());
            } else if (packet instanceof OpenDiagnosticsPacket open) {
                writeString(output, open.requestId().value());
            } else if (packet instanceof RefreshDiagnosticsPacket refresh) {
                writeString(output, refresh.requestId().value());
                writeString(output, refresh.sessionId().value());
                writeRevision(output, refresh.expectedRevision());
            } else if (packet instanceof HandshakeResultPacket result) {
                output.writeBoolean(result.accepted());
                writeString(output, result.reason());
            } else if (packet instanceof DiagnosticsSnapshotPacket snapshot) {
                writeSnapshot(output, snapshot.snapshot());
            } else if (packet instanceof DiagnosticsDeltaPacket delta) {
                writeDelta(output, delta.delta());
            } else if (packet instanceof DiagnosticsErrorPacket error) {
                writeString(output, error.requestId().value());
                writeError(output, error.error());
            } else {
                throw new PacketCodecException("unknown_discriminator");
            }
            output.flush();
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_PACKET_BYTES) {
                throw new PacketCodecException("oversize_packet");
            }
            return result;
        } catch (IOException | IllegalArgumentException failure) {
            if (failure instanceof PacketCodecException packetFailure) {
                throw packetFailure;
            }
            throw new PacketCodecException("malformed_packet", failure);
        }
    }

    public static FoundationPacket decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new PacketCodecException("malformed_packet");
        }
        if (bytes.length > MAX_PACKET_BYTES) {
            throw new PacketCodecException("oversize_packet");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            int discriminator = input.readUnsignedByte();
            FoundationPacket packet = switch (discriminator) {
                case 1 -> new ClientHelloPacket(readHandshake(input));
                case 2 -> new OpenDiagnosticsPacket(RequestId.of(readString(input)));
                case 3 -> new RefreshDiagnosticsPacket(
                        RequestId.of(readString(input)),
                        SessionId.of(readString(input)),
                        readRevision(input));
                case 4 -> new HandshakeResultPacket(input.readBoolean(), readString(input));
                case 5 -> new DiagnosticsSnapshotPacket(readSnapshot(input));
                case 6 -> new DiagnosticsDeltaPacket(readDelta(input));
                case 7 -> new DiagnosticsErrorPacket(
                        RequestId.of(readString(input)), readError(input));
                default -> throw new PacketCodecException("unknown_discriminator");
            };
            if (input.available() != 0) {
                throw new PacketCodecException("trailing_bytes");
            }
            return packet;
        } catch (PacketCodecException failure) {
            throw failure;
        } catch (EOFException | IllegalArgumentException failure) {
            throw new PacketCodecException("malformed_packet", failure);
        } catch (IOException failure) {
            throw new PacketCodecException("malformed_packet", failure);
        }
    }

    private static void writeHandshake(DataOutputStream output, FoundationHandshake handshake)
            throws IOException {
        output.writeInt(handshake.networkProtocol());
        writeString(output, handshake.apiSchema());
        writeString(output, handshake.modVersion());
        writeString(output, handshake.releaseTrain());
    }

    private static FoundationHandshake readHandshake(DataInputStream input) throws IOException {
        return new FoundationHandshake(
                input.readInt(), readString(input), readString(input), readString(input));
    }

    private static void writeSnapshot(DataOutputStream output, DiagnosticsSnapshot snapshot)
            throws IOException {
        writeString(output, snapshot.sessionId().value());
        writeRevision(output, snapshot.revision());
        writeString(output, snapshot.locale().value());
        output.writeBoolean(snapshot.streamerMode());
        output.writeBoolean(snapshot.adminAllowed());
        output.writeByte(snapshot.connectionState().ordinal());
        writeMap(output, snapshot.publicValues());
        writeMap(output, snapshot.adminValues());
        writeHealth(output, snapshot.serviceHealth());
    }

    private static DiagnosticsSnapshot readSnapshot(DataInputStream input) throws IOException {
        return DiagnosticsSnapshot.of(
                SessionId.of(readString(input)),
                readRevision(input),
                LocaleTag.of(readString(input)),
                input.readBoolean(),
                input.readBoolean(),
                readConnectionState(input),
                readMap(input),
                readMap(input),
                readHealth(input));
    }

    private static void writeDelta(DataOutputStream output, DiagnosticsDelta delta) throws IOException {
        writeString(output, delta.sessionId().value());
        writeRevision(output, delta.fromRevision());
        writeRevision(output, delta.toRevision());
        writeString(output, delta.locale().value());
        output.writeBoolean(delta.streamerMode());
        output.writeBoolean(delta.adminAllowed());
        output.writeByte(delta.connectionState().ordinal());
        writeMap(output, delta.updatedPublicValues());
        writeMap(output, delta.updatedAdminValues());
        writeKeys(output, delta.removedPublicKeys());
        writeKeys(output, delta.removedAdminKeys());
        writeHealth(output, delta.updatedServiceHealth());
        writeKeys(output, delta.removedServiceHealthIds());
        writeHealthOrder(output, delta.serviceHealthOrder());
    }

    private static DiagnosticsDelta readDelta(DataInputStream input) throws IOException {
        return DiagnosticsDelta.of(
                SessionId.of(readString(input)),
                readRevision(input),
                readRevision(input),
                LocaleTag.of(readString(input)),
                input.readBoolean(),
                input.readBoolean(),
                readConnectionState(input),
                readMap(input),
                readMap(input),
                readKeys(input),
                readKeys(input),
                readHealth(input),
                readKeys(input),
                readHealthOrder(input));
    }

    private static void writeError(DataOutputStream output, FoundationError error) throws IOException {
        writeString(output, error.codeValue());
        writeMap(output, error.parameters());
    }

    private static FoundationError readError(DataInputStream input) throws IOException {
        String wireCode = readString(input);
        FoundationErrorCode code = null;
        for (FoundationErrorCode candidate : FoundationErrorCode.values()) {
            if (candidate.code().equals(wireCode)) {
                code = candidate;
                break;
            }
        }
        if (code == null) {
            throw new PacketCodecException("unknown_error_code");
        }
        return FoundationError.of(code, readMap(input));
    }

    private static void writeRevision(DataOutputStream output, Revision revision) throws IOException {
        if (revision.value() < 0) {
            throw new PacketCodecException("malformed_revision");
        }
        output.writeLong(revision.value());
    }

    private static Revision readRevision(DataInputStream input) throws IOException {
        long value = input.readLong();
        if (value < 0) {
            throw new PacketCodecException("malformed_revision");
        }
        return Revision.of(value);
    }

    private static void writeMap(DataOutputStream output, Map<String, String> values) throws IOException {
        if (values.size() > MAX_MAP_ENTRIES) {
            throw new PacketCodecException("oversize_field_count");
        }
        output.writeShort(values.size());
        Map<String, String> sorted = new TreeMap<>(values);
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            writeString(output, entry.getKey());
            writeString(output, entry.getValue());
        }
    }

    private static Map<String, String> readMap(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        if (count > MAX_MAP_ENTRIES) {
            throw new PacketCodecException("oversize_field_count");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String key = readString(input);
            String value = readString(input);
            if (result.put(key, value) != null) {
                throw new PacketCodecException("duplicate_field");
            }
        }
        return result;
    }

    private static void writeKeys(DataOutputStream output, Set<String> keys) throws IOException {
        if (keys.size() > MAX_MAP_ENTRIES) {
            throw new PacketCodecException("oversize_field_count");
        }
        output.writeShort(keys.size());
        for (String key : keys.stream().sorted().toList()) {
            writeString(output, key);
        }
    }

    private static Set<String> readKeys(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        if (count > MAX_MAP_ENTRIES) {
            throw new PacketCodecException("oversize_field_count");
        }
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (int index = 0; index < count; index++) {
            if (!result.add(readString(input))) {
                throw new PacketCodecException("duplicate_field");
            }
        }
        return result;
    }

    private static void writeHealth(DataOutputStream output, List<ServiceHealth> health) throws IOException {
        if (health.size() > DiagnosticsSnapshot.MAX_SERVICES) {
            throw new PacketCodecException("oversize_service_count");
        }
        output.writeShort(health.size());
        for (ServiceHealth item : health) {
            writeString(output, item.serviceId());
            output.writeByte(item.status().ordinal());
            writeString(output, item.messageKey());
        }
    }

    private static List<ServiceHealth> readHealth(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        if (count > DiagnosticsSnapshot.MAX_SERVICES) {
            throw new PacketCodecException("oversize_service_count");
        }
        List<ServiceHealth> result = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int index = 0; index < count; index++) {
            String id = readString(input);
            if (!ids.add(id)) {
                throw new PacketCodecException("duplicate_service_health");
            }
            int ordinal = input.readUnsignedByte();
            if (ordinal >= ServiceHealth.Status.values().length) {
                throw new PacketCodecException("unknown_health_status");
            }
            result.add(ServiceHealth.of(
                    id, ServiceHealth.Status.values()[ordinal], readString(input)));
        }
        return result;
    }

    private static void writeHealthOrder(DataOutputStream output, List<String> order)
            throws IOException {
        if (order.size() > DiagnosticsSnapshot.MAX_SERVICES) {
            throw new PacketCodecException("oversize_service_count");
        }
        output.writeShort(order.size());
        for (String id : order) {
            writeString(output, id);
        }
    }

    private static List<String> readHealthOrder(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        if (count > DiagnosticsSnapshot.MAX_SERVICES) {
            throw new PacketCodecException("oversize_service_count");
        }
        List<String> result = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int index = 0; index < count; index++) {
            String id = readString(input);
            if (!ids.add(id)) {
                throw new PacketCodecException("duplicate_service_health");
            }
            result.add(id);
        }
        return result;
    }

    private static ConnectionState readConnectionState(DataInputStream input) throws IOException {
        int ordinal = input.readUnsignedByte();
        if (ordinal >= ConnectionState.values().length) {
            throw new PacketCodecException("unknown_connection_state");
        }
        return ConnectionState.values()[ordinal];
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            throw new PacketCodecException("malformed_string");
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_STRING_BYTES) {
            throw new PacketCodecException("oversize_string");
        }
        output.writeShort(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > MAX_STRING_BYTES) {
            throw new PacketCodecException("oversize_string");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new PacketCodecException("malformed_utf8", failure);
        }
    }
}
