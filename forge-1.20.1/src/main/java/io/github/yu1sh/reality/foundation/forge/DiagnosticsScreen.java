package io.github.yu1sh.reality.foundation.forge;

import io.github.yu1sh.reality.foundation.api.DiagnosticsSnapshot;
import io.github.yu1sh.reality.foundation.api.RefreshDiagnosticsPacket;
import io.github.yu1sh.reality.foundation.api.ServiceHealth;
import io.github.yu1sh.reality.identity.RequestId;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Native, keyboard-focusable, read-only System Status screen. */
public final class DiagnosticsScreen extends AbstractContainerScreen<DiagnosticsMenu> {
    static final int IMAGE_HEIGHT = 228;
    static final int CONTENT_TOP = 28;
    static final int CONTENT_BOTTOM = 150;
    static final int LIST_WIDGET_TOP = 158;
    static final int WIDGET_BOTTOM = 224;
    private static final int HEALTH_PAGE_SIZE = 7;
    private static final int DETAIL_LINES_PER_PAGE = 10;
    private static final int MAX_DETAIL_ROWS = 11;
    private static final Map<String, String> LABEL_KEYS = Map.ofEntries(
            Map.entry("foundation.connection", "foundation.gui.label.connection"),
            Map.entry("foundation.protocol", "foundation.gui.label.protocol"),
            Map.entry("foundation.api_schema", "foundation.gui.label.api_schema"),
            Map.entry("foundation.mod_version", "foundation.gui.label.mod_version"),
            Map.entry("foundation.release_train", "foundation.gui.label.release_train"),
            Map.entry("foundation.service_count", "foundation.gui.label.service_count"),
            Map.entry("foundation.audit", "foundation.gui.label.audit"),
            Map.entry("context_state", "foundation.gui.label.context_state"),
            Map.entry("registry_close_order", "foundation.gui.label.registry_close_order"),
            Map.entry("session_validation", "foundation.gui.label.session_validation"),
            Map.entry("recovery_command", "foundation.gui.label.recovery_command"));
    private static final Map<String, String> VALUE_KEYS = Map.ofEntries(
            Map.entry("active", "foundation.gui.value.active"),
            Map.entry("reverse_registration", "foundation.gui.value.reverse_registration"),
            Map.entry("actor_expiry_rate_revision", "foundation.gui.value.actor_expiry_rate_revision"),
            Map.entry("available", "foundation.gui.value.available"));

    private boolean healthTab;
    private boolean adminTab;
    private boolean detailView;
    private boolean detailSourceAdmin;
    private int healthPage;
    private int selectedDetailIndex = -1;
    private int detailPage;
    private List<Component> detailValues = List.of();
    private final List<DetailHit> detailHits = new ArrayList<>(MAX_DETAIL_ROWS);
    private Button overviewButton;
    private Button healthButton;
    private Button refreshButton;
    private Button adminButton;
    private Button previousHealthButton;
    private Button nextHealthButton;
    private Button detailsBackButton;
    private Button detailPreviousButton;
    private Button detailButton;
    private Button detailNextButton;

    public DiagnosticsScreen(DiagnosticsMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 320;
        imageHeight = IMAGE_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        overviewButton = addRenderableWidget(Button.builder(
                        Component.translatable("foundation.gui.tab.overview"),
                        button -> selectOverview())
                .bounds(leftPos + 12, topPos + LIST_WIDGET_TOP, 88, 20).build());
        healthButton = addRenderableWidget(Button.builder(
                        Component.translatable("foundation.gui.tab.health"),
                        button -> selectHealth())
                .bounds(leftPos + 104, topPos + LIST_WIDGET_TOP, 88, 20).build());
        adminButton = addRenderableWidget(Button.builder(
                        Component.translatable("foundation.gui.tab.admin"),
                        button -> selectAdmin())
                .bounds(leftPos + 196, topPos + LIST_WIDGET_TOP, 100, 20).build());
        previousHealthButton = addRenderableWidget(Button.builder(
                        Component.translatable("foundation.gui.health.previous"),
                        button -> healthPage = Math.max(0, healthPage - 1))
                .bounds(leftPos + 12, topPos + 181, 88, 20).build());
        nextHealthButton = addRenderableWidget(Button.builder(
                        Component.translatable("foundation.gui.health.next"),
                        button -> healthPage++)
                .bounds(leftPos + 104, topPos + 181, 88, 20).build());
        refreshButton = addRenderableWidget(Button.builder(
                        Component.translatable("foundation.gui.refresh"), button -> refresh())
                .bounds(leftPos + 196, topPos + 181, 100, 20).build());
        detailsBackButton = addRenderableWidget(Button.builder(
                        Component.translatable("foundation.gui.details.back"),
                        button -> leaveDetail())
                .bounds(leftPos + 12, topPos + LIST_WIDGET_TOP, 88, 20).build());
        detailPreviousButton = addRenderableWidget(Button.builder(
                        Component.translatable("foundation.gui.details.previous"),
                        button -> moveDetail(-1))
                .bounds(leftPos + 104, topPos + LIST_WIDGET_TOP, 88, 20).build());
        detailNextButton = addRenderableWidget(Button.builder(
                        Component.translatable("foundation.gui.details.next"),
                        button -> moveDetail(1))
                .bounds(leftPos + 196, topPos + LIST_WIDGET_TOP, 100, 20).build());
        detailButton = addRenderableWidget(Button.builder(
                        Component.translatable("foundation.gui.details"),
                        button -> enterDetail())
                .createNarration(ignored -> detailNarration())
                .bounds(leftPos + 104, topPos + 204, 88, 20).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("foundation.gui.close"), button -> onClose())
                .bounds(leftPos + 196, topPos + 204, 100, 20).build());
        updateTabVisibility();
    }

    private void selectOverview() {
        detailView = false;
        healthTab = false;
        adminTab = false;
    }

    private void selectHealth() {
        detailView = false;
        healthTab = true;
        adminTab = false;
    }

    private void selectAdmin() {
        if (menu.snapshot().map(DiagnosticsSnapshot::adminAllowed).orElse(false)) {
            detailView = false;
            healthTab = false;
            adminTab = true;
        } else {
            adminTab = false;
        }
    }

    private void refresh() {
        menu.snapshot().ifPresent(snapshot -> FoundationNetwork.sendToServer(
                new RefreshDiagnosticsPacket(
                        RequestId.of("refresh-" + UUID.randomUUID()),
                        snapshot.sessionId(), snapshot.revision())));
    }

    private void enterDetail() {
        if (detailValues.isEmpty()) {
            return;
        }
        detailView = true;
        detailSourceAdmin = adminTab;
        selectedDetailIndex = Math.max(0,
                Math.min(selectedDetailIndex, detailValues.size() - 1));
        detailPage = 0;
    }

    private void leaveDetail() {
        detailView = false;
        detailPage = 0;
    }

    private void moveDetail(int direction) {
        if (!detailView || detailValues.isEmpty()) {
            return;
        }
        List<String> lines = detailLines();
        int pages = DiagnosticsScreenLayout.pageCount(lines.size(), DETAIL_LINES_PER_PAGE);
        if (direction < 0) {
            if (detailPage > 0) {
                detailPage--;
            } else if (selectedDetailIndex > 0) {
                selectedDetailIndex--;
                detailPage = detailPageCountForSelected() - 1;
            }
        } else if (detailPage + 1 < pages) {
            detailPage++;
        } else if (selectedDetailIndex + 1 < detailValues.size()) {
            selectedDetailIndex++;
            detailPage = 0;
        }
    }

    private int detailPageCountForSelected() {
        return DiagnosticsScreenLayout.pageCount(detailLines().size(), DETAIL_LINES_PER_PAGE);
    }

    private List<String> detailLines() {
        if (selectedDetailIndex < 0 || selectedDetailIndex >= detailValues.size()) {
            return List.of();
        }
        return DiagnosticsScreenLayout.wrap(
                detailValues.get(selectedDetailIndex).getString(), 288, font::width);
    }

    private void updateTabVisibility() {
        boolean allowed = menu.snapshot().map(DiagnosticsSnapshot::adminAllowed).orElse(false);
        if (!allowed) {
            adminTab = false;
            if (detailView && detailSourceAdmin) {
                detailView = false;
                detailValues = List.of();
            }
        }
        boolean listView = !detailView;
        overviewButton.visible = listView;
        healthButton.visible = listView;
        adminButton.visible = listView && allowed;
        previousHealthButton.visible = listView && healthTab && healthPage > 0;
        nextHealthButton.visible = listView && healthTab && healthPage + 1 < healthPageCount();
        refreshButton.visible = listView;
        detailsBackButton.visible = detailView;
        detailPreviousButton.visible = detailView && canMoveDetail(-1);
        detailNextButton.visible = detailView && canMoveDetail(1);
        detailButton.visible = listView && !detailValues.isEmpty();
        overviewButton.active = overviewButton.visible;
        healthButton.active = healthButton.visible;
        adminButton.active = adminButton.visible;
        previousHealthButton.active = previousHealthButton.visible;
        nextHealthButton.active = nextHealthButton.visible;
        refreshButton.active = refreshButton.visible;
        detailsBackButton.active = detailsBackButton.visible;
        detailPreviousButton.active = detailPreviousButton.visible;
        detailNextButton.active = detailNextButton.visible;
        detailButton.active = detailButton.visible;
        if (listView) {
            healthPage = Math.min(healthPage, healthPageCount() - 1);
        } else if (!detailValues.isEmpty()) {
            selectedDetailIndex = Math.max(0,
                    Math.min(selectedDetailIndex, detailValues.size() - 1));
            detailPage = Math.min(detailPage, detailPageCountForSelected() - 1);
        }
    }

    private int healthPageCount() {
        return menu.snapshot()
                .map(snapshot -> DiagnosticsScreenLayout.pageCount(
                        snapshot.serviceHealth().size(), HEALTH_PAGE_SIZE))
                .orElse(1);
    }

    private boolean canMoveDetail(int direction) {
        if (detailValues.isEmpty() || selectedDetailIndex < 0) {
            return false;
        }
        if (direction < 0) {
            return detailPage > 0 || selectedDetailIndex > 0;
        }
        return detailPage + 1 < detailPageCountForSelected()
                || selectedDetailIndex + 1 < detailValues.size();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!detailView && healthTab && amount != 0.0) {
            healthPage = Math.max(0, healthPage + (amount < 0 ? 1 : -1));
            updateTabVisibility();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xE8101420);
        graphics.fill(leftPos + 8, topPos + CONTENT_TOP, leftPos + imageWidth - 8,
                topPos + CONTENT_BOTTOM, 0xE81E2533);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.translatable(
                "screen.reality_foundation.system_status"), 12, 10, 0xFFFFFF);
        menu.snapshot().ifPresent(snapshot -> {
            if (detailView) {
                renderSelectedDetail(graphics);
            } else if (healthTab) {
                renderHealth(graphics, snapshot);
            } else {
                renderValues(graphics, adminTab ? snapshot.adminValues() : snapshot.publicValues());
            }
        });
        menu.errorMessageKey().ifPresent(key -> graphics.drawString(
                font, Component.translatable(key), 16, 138, 0xFF6B6B));
    }

    private void renderValues(GuiGraphics graphics, Map<String, String> values) {
        int y = 36;
        for (Map.Entry<String, String> entry : values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).limit(MAX_DETAIL_ROWS).toList()) {
            String labelKey = LABEL_KEYS.getOrDefault(entry.getKey(), "foundation.gui.label.value");
            drawBounded(graphics, Component.translatable(labelKey), 16, y, 140, 0xE6EDF3);
            drawBounded(graphics, displayValue(entry.getKey(), entry.getValue()), 164, y, 140, 0xE6EDF3);
            y += 10;
        }
    }

    private void renderHealth(GuiGraphics graphics, DiagnosticsSnapshot snapshot) {
        int start = healthPage * HEALTH_PAGE_SIZE;
        int end = Math.min(snapshot.serviceHealth().size(), start + HEALTH_PAGE_SIZE);
        if (start >= end) {
            drawBounded(graphics, Component.translatable("foundation.gui.health.none"),
                    16, 40, 288, 0xE6EDF3);
            return;
        }
        int y = 36;
        for (ServiceHealth health : snapshot.serviceHealth().subList(start, end)) {
            Component status = Component.translatable("foundation.health."
                    + health.status().name().toLowerCase());
            Component message = translatedHealthMessage(health.messageKey());
            drawBounded(graphics, Component.translatable("foundation.gui.health.line",
                    health.serviceId(), status, message), 16, y, 288, 0xE6EDF3);
            y += 16;
        }
        int pageCount = Math.max(1,
                DiagnosticsScreenLayout.pageCount(snapshot.serviceHealth().size(), HEALTH_PAGE_SIZE));
        graphics.drawString(font, Component.translatable(
                "foundation.gui.health.page", healthPage + 1, pageCount), 16, 140, 0xFFFFFF);
    }

    private void renderSelectedDetail(GuiGraphics graphics) {
        List<String> lines = detailLines();
        if (lines.isEmpty()) {
            return;
        }
        int pageCount = DiagnosticsScreenLayout.pageCount(lines.size(), DETAIL_LINES_PER_PAGE);
        int start = detailPage * DETAIL_LINES_PER_PAGE;
        int end = Math.min(lines.size(), start + DETAIL_LINES_PER_PAGE);
        for (int index = start, y = 36; index < end; index++, y += font.lineHeight) {
            graphics.drawString(font, Component.literal(lines.get(index)), 16, y, 0xE6EDF3);
        }
        graphics.drawString(font, Component.translatable(
                "foundation.gui.details.selected", selectedDetailIndex + 1,
                detailValues.size(), detailPage + 1, pageCount), 16, 140, 0xFFFFFF);
    }

    private static Component translatedHealthMessage(String messageKey) {
        if (messageKey != null && Language.getInstance().has(messageKey)) {
            return Component.translatable(messageKey);
        }
        return Component.translatable("foundation.gui.health.message_unavailable");
    }

    private Component displayValue(String key, String value) {
        if ("foundation.connection".equals(key)) {
            return translatedOrLiteral("foundation.state." + value, value);
        }
        if ("foundation.audit".equals(key)) {
            return translatedOrLiteral("foundation.audit." + value, value);
        }
        return VALUE_KEYS.containsKey(value)
                ? Component.translatable(VALUE_KEYS.get(value))
                : Component.literal(value);
    }

    private Component translatedOrLiteral(String key, String fallback) {
        return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(fallback);
    }

    private void drawBounded(GuiGraphics graphics, Component full, int x, int y,
                            int maxWidth, int color) {
        String text = full.getString();
        String visible = DiagnosticsScreenLayout.ellipsize(text, maxWidth, font::width);
        if (!visible.equals(text) && detailHits.size() < MAX_DETAIL_ROWS) {
            detailHits.add(new DetailHit(x, y, maxWidth, font.lineHeight, full));
        }
        graphics.drawString(font, Component.literal(visible), x, y, color);
    }

    private MutableComponent detailNarration() {
        if (detailValues.isEmpty()) {
            return Component.translatable("foundation.gui.details");
        }
        int index = Math.max(0, Math.min(selectedDetailIndex, detailValues.size() - 1));
        return Component.translatable("foundation.gui.details.narration", detailValues.get(index));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateTabVisibility();
        detailHits.clear();
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!detailView) {
            detailValues = detailHits.stream().map(DetailHit::full).toList();
        }
        updateTabVisibility();
        renderTooltip(graphics, mouseX, mouseY);
        int localX = mouseX - leftPos;
        int localY = mouseY - topPos;
        for (DetailHit hit : detailHits) {
            if (hit.contains(localX, localY)) {
                graphics.renderTooltip(font, hit.full(), mouseX, mouseY);
                break;
            }
        }
    }

    private record DetailHit(int x, int y, int width, int height, Component full) {
        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + width
                    && mouseY >= y && mouseY < y + height;
        }
    }

}
