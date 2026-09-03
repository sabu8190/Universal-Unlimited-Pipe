package com.uup;

import com.uup.config.ModConfig;
import com.uup.logging.UUPLogger;
import com.uup.setup.ModBlockEntities;
import com.uup.setup.ModBlocks;
import com.uup.setup.ModCreativeTabs;
import com.uup.setup.ModItems;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(UniversalUnlimitedPipe.MODID)
public class UniversalUnlimitedPipe {

    public static final String MODID = "uup";

    public UniversalUnlimitedPipe() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModConfig.register();

        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModCreativeTabs.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);

        UUPLogger.info("Universal Unlimited Pipe (UUP) Mod loaded successfully.");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        UUPLogger.info("Universal Unlimited Pipe (UUP) Common Setup complete. Energy & Fluid Pipes initialized.");
    }
}
