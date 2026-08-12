package io.github.yu1sh.reality.foundation.forge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MalformedPacketAbuseGuardTest {
    @Test
    void guardBoundsAttemptsAndTrackedSendersWithoutUsingApplicationRateState() {
        MalformedPacketAbuseGuard guard = new MalformedPacketAbuseGuard();
        UUID sender = UUID.randomUUID();
        for (int attempt = 0; attempt < MalformedPacketAbuseGuard.MAX_ATTEMPTS_PER_WINDOW; attempt++) {
            assertFalse(guard.shouldDisconnect(sender, 100L + attempt));
        }
        assertTrue(guard.shouldDisconnect(sender, 200L));
        assertFalse(guard.shouldDisconnect(sender,
                100L + MalformedPacketAbuseGuard.WINDOW_NANOS));

        for (int index = 0; index < MalformedPacketAbuseGuard.MAX_TRACKED_SENDERS + 10; index++) {
            assertFalse(guard.shouldDisconnect(new UUID(0L, index + 1L),
                    MalformedPacketAbuseGuard.WINDOW_NANOS + 1L));
        }
        assertEquals(MalformedPacketAbuseGuard.MAX_TRACKED_SENDERS, guard.trackedSenderCount());
    }
}
