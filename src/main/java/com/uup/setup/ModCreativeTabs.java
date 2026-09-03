package com.uup.setup;

import com.uup.UniversalUnlimitedPipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, UniversalUnlimitedPipe.MODID);

    public static final RegistryObject<CreativeModeTab> TAB = CREATIVE_MODE_TABS.register("tab", () -> CreativeModeTab.builder()
            .icon(() -> new ItemStack(ModBlocks.PIPE.get()))
            .title(Component.translatable("itemGroup.uup"))
            .displayItems((params, output) -> {
                output.accept(ModBlocks.CONTROLLER.get());
                output.accept(ModBlocks.PIPE.get());
                output.accept(ModBlocks.ENERGY_PIPE.get());
                output.accept(ModBlocks.FLUID_PIPE.get());
                output.accept(ModBlocks.ITEM_PIPE.get());
                output.accept(ModBlocks.GAS_PIPE.get());
                output.accept(ModBlocks.NODE.get());
                output.accept(ModItems.OVERCLOCK_UPGRADE.get());
                output.accept(ModItems.NETWORK_CARD.get());
            })
            .build());

    public static void register(IEventBus bus) {
        CREATIVE_MODE_TABS.register(bus);
    }
}
