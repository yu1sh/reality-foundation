package io.github.yu1sh.reality.foundation.forge;

import io.github.yu1sh.reality.foundation.api.FoundationVersion;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Only a menu registry entry is added; no block, item, entity, or world id. */
public final class FoundationMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, FoundationVersion.MOD_ID);
    public static final RegistryObject<MenuType<DiagnosticsMenu>> DIAGNOSTICS = MENUS.register(
            "system_status", () -> IForgeMenuType.create(
                    (windowId, inventory, data) -> new DiagnosticsMenu(windowId, inventory)));

    private FoundationMenus() {
    }
}
