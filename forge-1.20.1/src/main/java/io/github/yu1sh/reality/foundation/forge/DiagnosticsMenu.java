package io.github.yu1sh.reality.foundation.forge;

import io.github.yu1sh.reality.foundation.api.DiagnosticsDelta;
import io.github.yu1sh.reality.foundation.api.DiagnosticsApplicationService;
import io.github.yu1sh.reality.foundation.api.DiagnosticsSnapshot;
import io.github.yu1sh.reality.identity.ActorId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/** Read-only native container; all state is received from the server. */
public final class DiagnosticsMenu extends AbstractContainerMenu {
    private DiagnosticsSnapshot snapshot;
    private String errorMessageKey;
    private final DiagnosticsApplicationService diagnostics;
    private final ActorId serverActor;
    private boolean lifecycleInvalidated;

    public DiagnosticsMenu(int windowId, Inventory inventory) {
        this(windowId, inventory, null, null, null);
    }

    private DiagnosticsMenu(
            int windowId,
            Inventory inventory,
            DiagnosticsSnapshot snapshot,
            DiagnosticsApplicationService diagnostics,
            ActorId serverActor) {
        super(FoundationMenus.DIAGNOSTICS.get(), windowId);
        this.snapshot = snapshot;
        this.diagnostics = diagnostics;
        this.serverActor = serverActor;
    }

    static DiagnosticsMenu server(
            int windowId,
            Inventory inventory,
            DiagnosticsSnapshot snapshot,
            DiagnosticsApplicationService diagnostics,
            ActorId serverActor) {
        return new DiagnosticsMenu(windowId, inventory, snapshot, diagnostics, serverActor);
    }

    public Optional<DiagnosticsSnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public void applySnapshot(DiagnosticsSnapshot snapshot) {
        if (this.snapshot != null && !this.snapshot.sessionId().equals(snapshot.sessionId())) {
            throw new IllegalArgumentException("snapshot_session_mismatch");
        }
        this.snapshot = snapshot;
        this.errorMessageKey = null;
    }

    public void applyDelta(DiagnosticsDelta delta) {
        if (snapshot == null) {
            throw new IllegalArgumentException("delta_without_snapshot");
        }
        snapshot = snapshot.apply(delta);
        errorMessageKey = null;
    }

    public void setErrorMessageKey(String errorMessageKey) {
        this.errorMessageKey = errorMessageKey;
    }

    public Optional<String> errorMessageKey() {
        return Optional.ofNullable(errorMessageKey);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        invalidateServerSession();
    }

    /** Server lifecycle hook kept separate so tests can exercise the same idempotent boundary. */
    void invalidateServerSession() {
        if (!lifecycleInvalidated && diagnostics != null && serverActor != null && snapshot != null) {
            lifecycleInvalidated = true;
            diagnostics.invalidateSession(serverActor, snapshot.sessionId());
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive();
    }
}
