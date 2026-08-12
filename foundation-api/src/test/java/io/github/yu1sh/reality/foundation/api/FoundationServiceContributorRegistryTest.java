package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.identity.ActorId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoundationServiceContributorRegistryTest {
    @Test
    void contributorsApplyInStableIdOrderAndAreRecreatedPerContext() {
        FoundationServiceContributorRegistry registry = new FoundationServiceContributorRegistry();
        List<String> applied = new ArrayList<>();
        registry.register(new TestContributor("zeta", applied));
        registry.register(new TestContributor("alpha", applied));
        List<FoundationServiceContributor> first = registry.freezeAndSnapshot();
        assertEquals(List.of("alpha", "zeta"), first.stream()
                .map(FoundationServiceContributor::id).toList());

        RealityServerContext firstContext = RealityServerContext.create(
                Clock.systemUTC(), new NoopAuditPort());
        registry.apply(firstContext);
        assertEquals(List.of("alpha", "zeta"), applied);
        firstContext.close();

        RealityServerContext secondContext = RealityServerContext.create(
                Clock.systemUTC(), new NoopAuditPort());
        registry.apply(secondContext);
        assertEquals(List.of("alpha", "zeta", "alpha", "zeta"), applied);
        secondContext.close();
        assertEquals(2, registry.size());
    }

    @Test
    void duplicateIdLimitAndPostStartRegistrationAreRejected() {
        FoundationServiceContributorRegistry registry = new FoundationServiceContributorRegistry();
        registry.register(new TestContributor("same", new ArrayList<>()));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new TestContributor("same", new ArrayList<>())));
        registry.freezeAndSnapshot();
        assertThrows(IllegalStateException.class,
                () -> registry.register(new TestContributor("later", new ArrayList<>())));
    }

    @Test
    void contributorFailureClosesPartialContextAndReportsContributor() {
        FoundationServiceContributorRegistry registry = new FoundationServiceContributorRegistry();
        AtomicBoolean closed = new AtomicBoolean();
        registry.register(new FoundationServiceContributor() {
            @Override
            public String id() {
                return "partial";
            }

            @Override
            public void contribute(RealityServerContext context) {
                context.services().register(
                        ServiceKey.of("partial.service", AutoCloseable.class),
                        () -> closed.set(true), "partial");
                throw new IllegalStateException("contributor failure");
            }
        });
        RealityServerContext context = RealityServerContext.create(
                Clock.systemUTC(), new NoopAuditPort());

        FoundationServiceContributorException failure = assertThrows(
                FoundationServiceContributorException.class, () -> registry.apply(context));
        assertEquals("partial", failure.contributorId());
        assertTrue(context.isClosed());
        assertTrue(closed.get(), "partial service must be rolled back by context close");
        assertThrows(IllegalStateException.class, context::diagnostics);
    }

    @Test
    void contributorRegistryIsBounded() {
        FoundationServiceContributorRegistry registry = new FoundationServiceContributorRegistry();
        for (int index = 0; index < FoundationServiceContributorRegistry.MAX_CONTRIBUTORS; index++) {
            registry.register(new TestContributor("c" + index, new ArrayList<>()));
        }
        assertThrows(IllegalStateException.class,
                () -> registry.register(new TestContributor("overflow", new ArrayList<>())));
        assertFalse(registry.size() > FoundationServiceContributorRegistry.MAX_CONTRIBUTORS);
    }

    private static final class TestContributor implements FoundationServiceContributor {
        private final String id;
        private final List<String> applied;

        private TestContributor(String id, List<String> applied) {
            this.id = id;
            this.applied = applied;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void contribute(RealityServerContext context) {
            applied.add(id);
            context.services().register(
                    ServiceKey.of("contributor." + id, ActorId.class),
                    ActorId.of(id), id);
        }
    }
}
