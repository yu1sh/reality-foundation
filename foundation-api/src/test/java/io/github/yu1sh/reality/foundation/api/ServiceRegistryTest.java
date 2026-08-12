package io.github.yu1sh.reality.foundation.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceRegistryTest {
    @Test
    void logicalIdIsUniqueAcrossTypesAndTypeSpoofingIsRejected() {
        ServiceRegistry registry = new ServiceRegistry();
        ServiceKey<String> textKey = ServiceKey.of("shared", String.class);
        ServiceKey<Integer> integerKey = ServiceKey.of("shared", Integer.class);

        registry.register(textKey, "value", "text-owner");

        assertThrows(IllegalStateException.class,
                () -> registry.register(integerKey, 42, "integer-owner"));
        assertThrows(IllegalArgumentException.class, () -> registry.require(integerKey));
        assertThrows(IllegalArgumentException.class, () -> registry.find(integerKey));
        assertThrows(IllegalArgumentException.class, () -> registry.unregister(integerKey));
        assertEquals("value", registry.require(textKey));
        assertEquals(1, registry.descriptors().size());
    }

    @Test
    void oneOwnerMayRegisterMultipleServicesAndClosedRegistryIsStable() {
        ServiceRegistry registry = new ServiceRegistry();
        ServiceKey<String> first = ServiceKey.of("first", String.class);
        ServiceKey<String> second = ServiceKey.of("second", String.class);
        registry.register(first, "one", "owner");
        registry.register(second, "two", "owner");
        assertEquals(List.of("first", "second"), registry.descriptors().stream()
                .map(ServiceDescriptor::serviceId).toList());

        registry.close();
        registry.close();
        assertTrue(registry.isClosed());
        assertThrows(IllegalStateException.class, () -> registry.find(first));
        assertThrows(IllegalStateException.class, () -> registry.register(second, "two", "owner2"));
    }

    @Test
    void closeRunsInReverseRegistrationOrder() {
        ServiceRegistry registry = new ServiceRegistry();
        StringBuilder order = new StringBuilder();
        registry.register(ServiceKey.of("first", AutoCloseable.class),
                () -> order.append('1'), "first-owner");
        registry.register(ServiceKey.of("second", AutoCloseable.class),
                () -> order.append('2'), "second-owner");
        registry.close();
        assertEquals("21", order.toString());
    }

    @Test
    void sameOwnerServicesUnregisterAndCloseIndependentlyInReverseOrder() {
        ServiceRegistry registry = new ServiceRegistry();
        StringBuilder order = new StringBuilder();
        ServiceKey<AutoCloseable> first = ServiceKey.of("same.first", AutoCloseable.class);
        ServiceKey<AutoCloseable> second = ServiceKey.of("same.second", AutoCloseable.class);
        registry.register(first, () -> order.append('1'), "one-mod");
        registry.register(second, () -> order.append('2'), "one-mod");
        assertTrue(registry.unregister(first).isPresent());
        assertEquals("1", order.toString());
        registry.close();
        assertEquals("12", order.toString());
    }

    @Test
    void healthCallbacksCanMutateRegistryWithoutCme() {
        ServiceRegistry registry = new ServiceRegistry();
        ServiceKey<HealthAwareService> selfKey = ServiceKey.of("self", HealthAwareService.class);
        ServiceKey<HealthAwareService> otherKey = ServiceKey.of("other", HealthAwareService.class);
        ServiceKey<HealthAwareService> addedKey = ServiceKey.of("added", HealthAwareService.class);

        HealthAwareService self = new HealthAwareService() {
            @Override
            public ServiceHealth health() {
                registry.unregister(selfKey);
                registry.register(addedKey,
                        () -> ServiceHealth.healthy("added"), "added-owner");
                return ServiceHealth.healthy("self");
            }
        };
        registry.register(selfKey, self, "self-owner");
        registry.register(otherKey,
                () -> ServiceHealth.healthy("other"), "other-owner");

        List<ServiceHealth> health = registry.healthSnapshot();
        assertEquals(List.of("self", "other"), health.stream()
                .map(ServiceHealth::serviceId).toList());
        assertEquals("added", registry.healthSnapshot().get(1).serviceId());
        assertEquals(2, registry.descriptors().size());
    }

    @Test
    void slowHealthDoesNotBlockRegisterOrCloseAndSnapshotOrderIsFixed() throws Exception {
        ServiceRegistry registry = new ServiceRegistry();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ServiceKey<HealthAwareService> slowKey = ServiceKey.of("slow", HealthAwareService.class);
        registry.register(slowKey, new HealthAwareService() {
            @Override
            public ServiceHealth health() {
                entered.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return ServiceHealth.healthy("slow");
            }
        }, "slow-owner");

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<List<ServiceHealth>> healthFuture = executor.submit(registry::healthSnapshot);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<?> registerFuture = executor.submit(() -> registry.register(
                    ServiceKey.of("parallel", String.class), "ok", "parallel-owner"));
            registerFuture.get(2, TimeUnit.SECONDS);
            Future<?> closeFuture = executor.submit(registry::close);
            closeFuture.get(2, TimeUnit.SECONDS);
            release.countDown();
            assertEquals("slow", healthFuture.get(2, TimeUnit.SECONDS).get(0).serviceId());
            assertTrue(registry.isClosed());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void throwingHealthBecomesUnavailableWithoutBreakingRegistry() {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(ServiceKey.of("broken", HealthAwareService.class),
                new HealthAwareService() {
                    @Override
                    public ServiceHealth health() {
                        throw new IllegalStateException("test callback failure");
                    }
                }, "broken-owner");
        assertEquals(ServiceHealth.Status.UNAVAILABLE,
                registry.healthSnapshot().get(0).status());
        assertFalse(registry.isClosed());
    }

    @Test
    void healthCallbackCannotRenameItsRegisteredService() {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(ServiceKey.of("authoritative", HealthAwareService.class),
                () -> ServiceHealth.healthy("spoofed"), "same-owner");
        ServiceHealth observed = registry.healthSnapshot().get(0);
        assertEquals("authoritative", observed.serviceId());
        assertEquals(ServiceHealth.Status.UNAVAILABLE, observed.status());
    }
}
