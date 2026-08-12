package io.github.yu1sh.reality.foundation.forge;

import io.github.yu1sh.reality.foundation.api.DiagnosticsSnapshot;
import io.github.yu1sh.reality.foundation.api.DiagnosticsApplicationService;
import io.github.yu1sh.reality.identity.ActorId;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Server-created menu provider carrying no client-authoritative state. */
final class DiagnosticsMenuProvider implements MenuProvider {
    private final DiagnosticsSnapshot snapshot;
    private final DiagnosticsApplicationService diagnostics;
    private final ActorId actor;

    DiagnosticsMenuProvider(
            DiagnosticsSnapshot snapshot,
            DiagnosticsApplicationService diagnostics,
            ActorId actor) {
        this.snapshot = snapshot;
        this.diagnostics = diagnostics;
        this.actor = actor;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("screen.reality_foundation.system_status");
    }

    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player player) {
        return DiagnosticsMenu.server(windowId, inventory, snapshot, diagnostics, actor);
    }
}
