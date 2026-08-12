package io.github.yu1sh.reality.foundation.forge;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Small network-boundary guard for packets that cannot be decoded into an
 * actor-identifiable Foundation request. It is deliberately independent from
 * {@code DiagnosticsApplicationService}: malformed bytes have no validated
 * request or actor to pass into the application boundary.
 */
final class MalformedPacketAbuseGuard {
    static final int MAX_TRACKED_SENDERS = 256;
    static final int MAX_ATTEMPTS_PER_WINDOW = 4;
    static final long WINDOW_NANOS = 10_000_000_000L;

    private final LinkedHashMap<UUID, Window> windows = new LinkedHashMap<>();

    /** Returns true only after the bounded sender window is exhausted. */
    synchronized boolean shouldDisconnect(UUID sender, long nowNanos) {
        if (sender == null) {
            return true;
        }
        purgeExpired(nowNanos);
        Window window = windows.get(sender);
        if (window == null || elapsed(window.startedAtNanos, nowNanos) >= WINDOW_NANOS) {
            ensureCapacity();
            windows.put(sender, new Window(nowNanos, 1));
            return false;
        }
        if (window.attempts >= MAX_ATTEMPTS_PER_WINDOW) {
            return true;
        }
        window.attempts++;
        return false;
    }

    synchronized int trackedSenderCount() {
        return windows.size();
    }

    private void purgeExpired(long nowNanos) {
        Iterator<Map.Entry<UUID, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            Window window = iterator.next().getValue();
            if (elapsed(window.startedAtNanos, nowNanos) >= WINDOW_NANOS) {
                iterator.remove();
            }
        }
    }

    private void ensureCapacity() {
        while (windows.size() >= MAX_TRACKED_SENDERS) {
            Iterator<UUID> iterator = windows.keySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private static long elapsed(long startNanos, long nowNanos) {
        // nanoTime wrapping is immaterial for a ten-second window. Saturate a
        // malformed or wrapped negative difference so stale entries expire.
        long difference = nowNanos - startNanos;
        return difference < 0L ? Long.MAX_VALUE : difference;
    }

    private static final class Window {
        private final long startedAtNanos;
        private int attempts;

        private Window(long startedAtNanos, int attempts) {
            this.startedAtNanos = startedAtNanos;
            this.attempts = attempts;
        }
    }
}
