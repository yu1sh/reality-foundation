package io.github.yu1sh.reality.foundation.api;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealityServerContextTest {
    @Test
    void closeIsVisibleIdempotentAndRejectsFurtherUse() throws Exception {
        RealityServerContext context = RealityServerContext.create(
                Clock.systemUTC(), new NoopAuditPort());
        DiagnosticsApplicationService diagnostics = context.diagnostics();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> closes = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                closes.add(executor.submit(context::close));
            }
            for (Future<?> close : closes) {
                close.get();
            }
            assertTrue(context.isClosed());
            assertEquals(FoundationErrorCode.INTERNAL_FAILURE,
                    diagnostics.sessionCount().error().orElseThrow().code());
        } finally {
            executor.shutdownNow();
        }
    }
}
