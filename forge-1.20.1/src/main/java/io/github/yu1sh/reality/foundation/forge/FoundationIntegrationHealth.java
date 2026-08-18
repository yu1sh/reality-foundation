package io.github.yu1sh.reality.foundation.forge;

import com.mojang.logging.LogUtils;
import io.github.yu1sh.reality.foundation.api.FoundationServiceContributor;
import io.github.yu1sh.reality.foundation.api.HealthAwareService;
import io.github.yu1sh.reality.foundation.api.RealityServerContext;
import io.github.yu1sh.reality.foundation.api.ServiceHealth;
import io.github.yu1sh.reality.foundation.api.ServiceKey;
import io.github.yu1sh.reality.foundation.api.FoundationVersion;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Foundation-owned, read-only aggregation of the P-05/P-07/P-08 runtime seam.
 * The child Mods remain the owners of endpoint registration and lifecycle
 * state; this class only turns their bounded IMC reports into health entries.
 */
final class FoundationIntegrationHealth implements FoundationServiceContributor {
    static final String IMC_METHOD = "foundation_health_v1";
    static final int REPORT_VERSION = 1;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String REPORT_VERSION_TAG = "report_version";
    private static final String SERVICE_ID_TAG = "service_id";
    private static final String KIND_TAG = "kind";
    private static final String RUNTIME_STATE_TAG = "runtime_state";
    private static final String REGISTRATION_ISSUE_TAG = "registration_issue";
    private static final String STATUS_VERSION_TAG = "status_version";
    private static final String ADMIN_STATUS_VERSION_TAG = "admin_status_version";
    private static final String ENDPOINTS_TAG = "endpoints";
    private static final String METHOD_TAG = "method";
    private static final String VERSION_TAG = "version";
    private static final String STATE_TAG = "state";
    private static final String SERVICE_PREFIX = "foundation.integration.";

    private static final List<IntegrationSpec> SPECS = List.of(
            new IntegrationSpec(
                    "reality_quests",
                    "reality.quests",
                    "consumer",
                    List.of(
                            new EndpointSpec("quest_reward_scope_v2", 2),
                            new EndpointSpec("quest_reward_receive_v2", 2),
                            new EndpointSpec("reality_inventory_consume_v2", 2),
                            new EndpointSpec("reality_inventory_recover_v1", 1)),
                    true),
            new IntegrationSpec(
                    "reality_economy",
                    "reality.economy",
                    "provider",
                    List.of(
                            new EndpointSpec("quest_reward_scope_v2", 2),
                            new EndpointSpec("quest_reward_receive_v2", 2)),
                    false),
            new IntegrationSpec(
                    "reality_inventory",
                    "reality.inventory",
                    "provider",
                    List.of(
                            new EndpointSpec("reality_inventory_consume_v2", 2),
                            new EndpointSpec("reality_inventory_recover_v1", 1)),
                    false));

    private final Object monitor = new Object();
    private final Map<String, List<ReportProvider>> reportsBySender = new HashMap<>();
    private volatile MinecraftServer server;

    @Override
    public String id() {
        return "foundation.integration";
    }

    /** Receives only the three approved child-Mod report senders. */
    void processInterModMessages(InterModProcessEvent event) {
        InterModComms.getMessages(
                        FoundationVersion.MOD_ID,
                        sender -> SPECS.stream().anyMatch(spec -> spec.senderModId().equals(sender)))
                .forEach(message -> {
                    if (IMC_METHOD.equals(message.getMethod())) {
                        accept(message.getSenderModId(), message.getMessageSupplier());
                    } else {
                        acceptFailure(message.getSenderModId(), "method_mismatch");
                    }
                });
    }

    private void acceptFailure(String senderModId, String failureKey) {
        synchronized (monitor) {
            reportsBySender.computeIfAbsent(senderModId, ignored -> new ArrayList<>())
                    .add(ReportProvider.failure(failureKey));
        }
    }

    private void accept(String senderModId, Supplier<?> supplier) {
        ReportProvider provider;
        if (supplier == null) {
            provider = ReportProvider.failure("supplier_missing");
        } else {
            try {
                Object candidate = supplier.get();
                if (!(candidate instanceof Function<?, ?>)) {
                    provider = ReportProvider.failure("supplier_type");
                } else {
                    @SuppressWarnings("unchecked")
                    Function<MinecraftServer, ?> reportFunction = (Function<MinecraftServer, ?>) candidate;
                    provider = ReportProvider.of(reportFunction);
                }
            } catch (RuntimeException failure) {
                provider = ReportProvider.failure("supplier_failed");
                LOGGER.error("foundation_integration_report_supplier_failed sender={}", senderModId, failure);
            }
        }
        synchronized (monitor) {
            reportsBySender.computeIfAbsent(senderModId, ignored -> new ArrayList<>()).add(provider);
        }
    }

    void bindServer(MinecraftServer server) {
        this.server = server;
    }

    void clearServer(MinecraftServer server) {
        if (this.server == server) {
            this.server = null;
        }
    }

    @Override
    public void contribute(RealityServerContext context) {
        for (IntegrationSpec spec : SPECS) {
            String serviceId = SERVICE_PREFIX + spec.serviceId();
            context.services().register(
                    ServiceKey.of(serviceId, HealthAwareService.class),
                    new IntegrationHealthAwareService(serviceId, spec, this),
                    "foundation");
        }
    }

    private ServiceHealth health(IntegrationSpec spec, String serviceId) {
        MinecraftServer activeServer = server;
        if (activeServer == null) {
            return ServiceHealth.of(
                    serviceId,
                    ServiceHealth.Status.UNAVAILABLE,
                    "foundation.health.integration.missing");
        }

        List<ReportProvider> providers;
        synchronized (monitor) {
            providers = List.copyOf(reportsBySender.getOrDefault(spec.senderModId(), List.of()));
        }
        if (providers.isEmpty()) {
            return ServiceHealth.of(
                    serviceId,
                    ServiceHealth.Status.UNAVAILABLE,
                    "foundation.health.integration.missing");
        }
        if (providers.size() != 1) {
            return ServiceHealth.of(
                    serviceId,
                    ServiceHealth.Status.DEGRADED,
                    "foundation.health.integration.duplicate");
        }

        ReportProvider provider = providers.get(0);
        if (provider.failureKey() != null) {
            return ServiceHealth.of(
                    serviceId,
                    ServiceHealth.Status.UNAVAILABLE,
                    "foundation.health.integration.malformed");
        }

        CompoundTag report;
        try {
            Object result = provider.function().apply(activeServer);
            if (!(result instanceof CompoundTag candidate)) {
                return ServiceHealth.of(
                        serviceId,
                        ServiceHealth.Status.UNAVAILABLE,
                        "foundation.health.integration.malformed");
            }
            report = candidate;
        } catch (RuntimeException failure) {
            LOGGER.error("foundation_integration_report_failed sender={}", spec.senderModId(), failure);
            return ServiceHealth.of(
                    serviceId,
                    ServiceHealth.Status.UNAVAILABLE,
                    "foundation.health.integration.initialization_failed");
        }
        return evaluate(spec, serviceId, report);
    }

    private static ServiceHealth evaluate(
            IntegrationSpec spec, String serviceId, CompoundTag report) {
        if (report == null
                || !report.contains(REPORT_VERSION_TAG, Tag.TAG_INT)
                || report.getInt(REPORT_VERSION_TAG) != REPORT_VERSION
                || !report.contains(SERVICE_ID_TAG, Tag.TAG_STRING)
                || !spec.serviceId().equals(report.getString(SERVICE_ID_TAG))
                || !report.contains(KIND_TAG, Tag.TAG_STRING)
                || !spec.kind().equals(report.getString(KIND_TAG))) {
            return health(serviceId, ServiceHealth.Status.UNAVAILABLE,
                    "foundation.health.integration.malformed");
        }

        String runtimeState = readText(report, RUNTIME_STATE_TAG);
        if (!"ready".equals(runtimeState)) {
            return health(serviceId, ServiceHealth.Status.UNAVAILABLE,
                    "foundation.health.integration.initialization_failed");
        }

        String registrationIssue = readText(report, REGISTRATION_ISSUE_TAG);
        if (registrationIssue == null || switch (registrationIssue) {
            case "none" -> false;
            case "duplicate" -> true;
            case "incompatible" -> true;
            case "failure" -> true;
            default -> true;
        }) {
            if ("duplicate".equals(registrationIssue)) {
                return health(serviceId, ServiceHealth.Status.DEGRADED,
                        "foundation.health.integration.duplicate");
            }
            if ("incompatible".equals(registrationIssue)) {
                return health(serviceId, ServiceHealth.Status.DEGRADED,
                        "foundation.health.integration.incompatible");
            }
            if ("failure".equals(registrationIssue)) {
                return health(serviceId, ServiceHealth.Status.UNAVAILABLE,
                        "foundation.health.integration.initialization_failed");
            }
            return health(serviceId, ServiceHealth.Status.UNAVAILABLE,
                    "foundation.health.integration.malformed");
        }

        if (spec.statusContracts()) {
            if (!report.contains(STATUS_VERSION_TAG, Tag.TAG_INT)
                    || report.getInt(STATUS_VERSION_TAG) != 2
                    || !report.contains(ADMIN_STATUS_VERSION_TAG, Tag.TAG_INT)
                    || report.getInt(ADMIN_STATUS_VERSION_TAG) != 2) {
                return health(serviceId, ServiceHealth.Status.DEGRADED,
                        "foundation.health.integration.incompatible");
            }
        }

        if (!report.contains(ENDPOINTS_TAG, Tag.TAG_LIST)) {
            return health(serviceId, ServiceHealth.Status.UNAVAILABLE,
                    "foundation.health.integration.malformed");
        }
        ListTag endpointTags = report.getList(ENDPOINTS_TAG, Tag.TAG_COMPOUND);
        if (endpointTags.size() > spec.endpoints().size() + 4) {
            return health(serviceId, ServiceHealth.Status.UNAVAILABLE,
                    "foundation.health.integration.malformed");
        }
        Map<String, EndpointReport> endpoints = new HashMap<>();
        for (int index = 0; index < endpointTags.size(); index++) {
            CompoundTag endpoint = endpointTags.getCompound(index);
            String method = readText(endpoint, METHOD_TAG);
            if (method == null
                    || !endpoint.contains(VERSION_TAG, Tag.TAG_INT)
                    || !endpoint.contains(STATE_TAG, Tag.TAG_STRING)
                    || endpoints.put(method, new EndpointReport(
                    endpoint.getInt(VERSION_TAG), endpoint.getString(STATE_TAG))) != null) {
                return health(serviceId, ServiceHealth.Status.DEGRADED,
                        "foundation.health.integration.duplicate");
            }
        }

        Set<String> expectedMethods = new HashSet<>();
        boolean missing = false;
        boolean incompatible = false;
        boolean duplicate = false;
        boolean failure = false;
        for (EndpointSpec expected : spec.endpoints()) {
            expectedMethods.add(expected.method());
            EndpointReport actual = endpoints.get(expected.method());
            if (actual == null) {
                missing = true;
                continue;
            }
            if (actual.version() != expected.version()
                    || "incompatible".equals(actual.state())) {
                incompatible = true;
            } else if ("duplicate".equals(actual.state())) {
                duplicate = true;
            } else if ("failure".equals(actual.state())) {
                failure = true;
            } else if (spec.kind().equals("consumer")
                    && !"registered".equals(actual.state())) {
                missing = true;
            } else if (spec.kind().equals("provider")
                    && !"available".equals(actual.state())) {
                failure = true;
            }
        }
        if (endpoints.keySet().stream().anyMatch(method -> !expectedMethods.contains(method))) {
            incompatible = true;
        }
        if (duplicate) {
            return health(serviceId, ServiceHealth.Status.DEGRADED,
                    "foundation.health.integration.duplicate");
        }
        if (incompatible) {
            return health(serviceId, ServiceHealth.Status.DEGRADED,
                    "foundation.health.integration.incompatible");
        }
        if (failure) {
            return health(serviceId, ServiceHealth.Status.UNAVAILABLE,
                    "foundation.health.integration.initialization_failed");
        }
        if (missing) {
            return health(serviceId, ServiceHealth.Status.UNAVAILABLE,
                    "foundation.health.integration.missing");
        }
        return health(serviceId, ServiceHealth.Status.HEALTHY,
                "foundation.health.integration.available");
    }

    private static String readText(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_STRING) && !tag.getString(key).isBlank()
                ? tag.getString(key)
                : null;
    }

    private static ServiceHealth health(
            String serviceId, ServiceHealth.Status status, String messageKey) {
        return ServiceHealth.of(serviceId, status, messageKey);
    }

    private record IntegrationHealthAwareService(
            String serviceId,
            IntegrationSpec spec,
            FoundationIntegrationHealth owner) implements HealthAwareService {
        @Override
        public ServiceHealth health() {
            return owner.health(spec, serviceId);
        }
    }

    private record IntegrationSpec(
            String senderModId,
            String serviceId,
            String kind,
            List<EndpointSpec> endpoints,
            boolean statusContracts) {
    }

    private record EndpointSpec(String method, int version) {
    }

    private record EndpointReport(int version, String state) {
    }

    private record ReportProvider(Function<MinecraftServer, ?> function, String failureKey) {
        static ReportProvider of(Function<MinecraftServer, ?> function) {
            return new ReportProvider(function, null);
        }

        static ReportProvider failure(String key) {
            return new ReportProvider(null, key);
        }
    }
}
