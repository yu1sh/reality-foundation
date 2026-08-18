package io.github.yu1sh.reality.foundation.forge;

import io.github.yu1sh.reality.foundation.api.FoundationVersion;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Thin Forge composition root; domain state remains in reality-foundation-api. */
@Mod(FoundationVersion.MOD_ID)
public final class RealityFoundationMod {
    private static RealityFoundationMod instance;

    private final FoundationRuntime runtime;

    public RealityFoundationMod() {
        instance = this;
        this.runtime = new FoundationRuntime();
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        FoundationMenus.MENUS.register(modBus);
        FoundationNetwork.register();
        modBus.addListener(runtime::processInterModMessages);
        MinecraftForge.EVENT_BUS.register(new FoundationForgeEvents(runtime));
        DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> FoundationClient.register(modBus, runtime));
    }

    public static RealityFoundationMod instance() {
        if (instance == null) {
            throw new IllegalStateException("Reality Foundation mod is not initialized");
        }
        return instance;
    }

    public FoundationRuntime runtime() {
        return runtime;
    }
}
