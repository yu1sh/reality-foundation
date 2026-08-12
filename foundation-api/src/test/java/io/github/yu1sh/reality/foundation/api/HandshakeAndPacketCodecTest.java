package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.gui.LocaleTag;
import io.github.yu1sh.reality.identity.RequestId;
import io.github.yu1sh.reality.identity.SessionId;
import io.github.yu1sh.reality.version.Revision;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandshakeAndPacketCodecTest {
    @Test
    void handshakeRequiresExactProtocolSchemaVersionAndTrain() {
        HandshakeValidator validator = new HandshakeValidator(FoundationHandshake.current());
        assertEquals("accepted", validator.evaluate(FoundationHandshake.current()).stableReason());
        assertEquals(HandshakeRejectReason.PROTOCOL_MISMATCH,
                validator.evaluate(new FoundationHandshake(2,
                        FoundationVersion.API_SCHEMA, FoundationVersion.MOD_VERSION,
                        FoundationVersion.RELEASE_TRAIN)).rejection().orElseThrow());
        assertEquals(HandshakeRejectReason.RELEASE_TRAIN_MISMATCH,
                validator.evaluate(new FoundationHandshake(1,
                        FoundationVersion.API_SCHEMA, FoundationVersion.MOD_VERSION,
                        "rt0-bootstrap")).rejection().orElseThrow());
        assertEquals(HandshakeRejectReason.MOD_VERSION_MISMATCH,
                validator.evaluate(new FoundationHandshake(1,
                        FoundationVersion.API_SCHEMA, "0.2.0", FoundationVersion.RELEASE_TRAIN))
                        .rejection().orElseThrow());
    }

    @Test
    void packetRoundTripsAndPreservesNoMutationOperationOnRefresh() {
        FoundationPacket hello = new ClientHelloPacket(FoundationHandshake.current());
        assertEquals(hello, FoundationPacketCodec.decode(FoundationPacketCodec.encode(hello)));
        FoundationPacket open = new OpenDiagnosticsPacket(RequestId.of("request-1"));
        assertEquals(open, FoundationPacketCodec.decode(FoundationPacketCodec.encode(open)));
        FoundationPacket refresh = new RefreshDiagnosticsPacket(
                RequestId.of("request-2"), SessionId.of("session-1"), Revision.of(4));
        FoundationPacket decoded = FoundationPacketCodec.decode(FoundationPacketCodec.encode(refresh));
        assertEquals(refresh, decoded);
        assertEquals(Revision.of(4), ((RefreshDiagnosticsPacket) decoded).expectedRevision());
    }

    @Test
    void snapshotDeltaAndErrorPacketsRoundTripWithBounds() {
        DiagnosticsSnapshot snapshot = DiagnosticsSnapshot.of(
                SessionId.of("session-1"), Revision.of(1), LocaleTag.of("ja-JP"),
                false, true, ConnectionState.HANDSHAKE_ACCEPTED,
                Map.of("foundation.connection", "handshake_accepted"),
                Map.of("context_state", "active"),
                List.of(ServiceHealth.healthy("foundation.health")));
        FoundationPacket snapshotPacket = new DiagnosticsSnapshotPacket(snapshot);
        assertEquals(snapshotPacket,
                FoundationPacketCodec.decode(FoundationPacketCodec.encode(snapshotPacket)));

        DiagnosticsDelta delta = DiagnosticsDelta.of(
                snapshot.sessionId(), Revision.of(1), Revision.of(2), LocaleTag.of("en-US"),
                true, false, ConnectionState.REJECTED,
                Map.of("foundation.connection", "rejected"), Map.of(), Set.of(),
                Set.of("context_state"), List.of());
        FoundationPacket deltaPacket = new DiagnosticsDeltaPacket(delta);
        assertEquals(deltaPacket,
                FoundationPacketCodec.decode(FoundationPacketCodec.encode(deltaPacket)));

        FoundationPacket error = new DiagnosticsErrorPacket(
                RequestId.of("request-3"), FoundationError.of(FoundationErrorCode.RATE_LIMITED));
        assertEquals(error, FoundationPacketCodec.decode(FoundationPacketCodec.encode(error)));
    }

    @Test
    void malformedUnknownTrailingAndOversizePacketsAreRejected() {
        assertEquals("unknown_discriminator", assertThrows(PacketCodecException.class,
                () -> FoundationPacketCodec.decode(new byte[] {99})).reason());
        byte[] trailing = FoundationPacketCodec.encode(new OpenDiagnosticsPacket(RequestId.of("r")));
        byte[] withTrailing = java.util.Arrays.copyOf(trailing, trailing.length + 1);
        withTrailing[withTrailing.length - 1] = 1;
        assertEquals("trailing_bytes", assertThrows(PacketCodecException.class,
                () -> FoundationPacketCodec.decode(withTrailing)).reason());

        ByteBuffer oversizeString = ByteBuffer.allocate(1 + 2 + 513);
        oversizeString.put((byte) 2).putShort((short) 513)
                .put(new byte[513]);
        assertEquals("oversize_string", assertThrows(PacketCodecException.class,
                () -> FoundationPacketCodec.decode(oversizeString.array())).reason());

        byte[] oversizePacket = new byte[FoundationPacketCodec.MAX_PACKET_BYTES + 1];
        assertEquals("oversize_packet", assertThrows(PacketCodecException.class,
                () -> FoundationPacketCodec.decode(oversizePacket)).reason());
    }

    @Test
    void healthDeltaCarriesOnlyOneChangedServiceAmongSixtyFour() {
        List<ServiceHealth> beforeHealth = new ArrayList<>();
        List<ServiceHealth> afterHealth = new ArrayList<>();
        for (int index = 0; index < DiagnosticsSnapshot.MAX_SERVICES; index++) {
            String id = "service" + index;
            beforeHealth.add(ServiceHealth.healthy(id));
            afterHealth.add(index == 37
                    ? ServiceHealth.of(id, ServiceHealth.Status.DEGRADED,
                    "foundation.health.degraded")
                    : ServiceHealth.healthy(id));
        }
        DiagnosticsSnapshot before = DiagnosticsSnapshot.of(
                SessionId.of("health-delta"), Revision.of(1), LocaleTag.of("en-US"),
                false, false, ConnectionState.HANDSHAKE_ACCEPTED,
                Map.of(), Map.of(), beforeHealth);
        DiagnosticsSnapshot after = DiagnosticsSnapshot.of(
                before.sessionId(), Revision.of(2), before.locale(), false, false,
                before.connectionState(), Map.of(), Map.of(), afterHealth);

        DiagnosticsDelta delta = DiagnosticsDelta.between(before, after);
        assertEquals(1, delta.updatedServiceHealth().size());
        assertTrue(delta.serviceHealthOrder().isEmpty());
        assertEquals(after, before.apply(delta));
        assertTrue(FoundationPacketCodec.encode(new DiagnosticsDeltaPacket(delta)).length < 1024,
                "a one-service health change must not serialize all 64 health values");
        assertEquals(new DiagnosticsDeltaPacket(delta), FoundationPacketCodec.decode(
                FoundationPacketCodec.encode(new DiagnosticsDeltaPacket(delta))));
    }

    @Test
    void healthOrderSetMismatchIsRejectedBeforeClientStateChanges() {
        DiagnosticsSnapshot before = DiagnosticsSnapshot.of(
                SessionId.of("health-order"), Revision.of(1), LocaleTag.of("en-US"),
                false, false, ConnectionState.HANDSHAKE_ACCEPTED, Map.of(), Map.of(),
                List.of(ServiceHealth.healthy("first"), ServiceHealth.healthy("second")));
        DiagnosticsDelta malformed = DiagnosticsDelta.of(
                before.sessionId(), Revision.of(1), Revision.of(2), before.locale(),
                false, false, before.connectionState(), Map.of(), Map.of(), Set.of(), Set.of(),
                List.of(), Set.of(), List.of("second"));
        assertThrows(IllegalArgumentException.class, () -> before.apply(malformed));

        DiagnosticsDelta unknown = DiagnosticsDelta.of(
                before.sessionId(), Revision.of(1), Revision.of(2), before.locale(),
                false, false, before.connectionState(), Map.of(), Map.of(), Set.of(), Set.of(),
                List.of(), Set.of(), List.of("first", "second", "unknown"));
        byte[] encoded = FoundationPacketCodec.encode(new DiagnosticsDeltaPacket(unknown));
        DiagnosticsDelta decoded = ((DiagnosticsDeltaPacket) FoundationPacketCodec.decode(encoded)).delta();
        assertThrows(IllegalArgumentException.class, () -> before.apply(decoded));
    }
}
