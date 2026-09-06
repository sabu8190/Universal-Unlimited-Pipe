package com.uup.setup;

import com.uup.UniversalUnlimitedPipe;
import com.uup.item.NetworkCardItem;
import com.uup.item.OverclockUpgradeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, UniversalUnlimitedPipe.MODID);

    public static final RegistryObject<Item> CONTROLLER = ITEMS.register("controller", () -> new BlockItem(ModBlocks.CONTROLLER.get(), new Item.Properties()));
    public static final RegistryObject<Item> PROCESSOR_1_81 = ITEMS.register("processor_1_81", () -> new BlockItem(ModBlocks.PROCESSOR_1_81.get(), new Item.Properties()));
    public static final RegistryObject<Item> PROCESSOR_1_9 = ITEMS.register("processor_1_9", () -> new BlockItem(ModBlocks.PROCESSOR_1_9.get(), new Item.Properties()));
    public static final RegistryObject<Item> PIPE = ITEMS.register("pipe", () -> new BlockItem(ModBlocks.PIPE.get(), new Item.Properties()));
    public static final RegistryObject<Item> ENERGY_PIPE = ITEMS.register("energy_pipe", () -> new BlockItem(ModBlocks.ENERGY_PIPE.get(), new Item.Properties()));
    public static final RegistryObject<Item> FLUID_PIPE = ITEMS.register("fluid_pipe", () -> new BlockItem(ModBlocks.FLUID_PIPE.get(), new Item.Properties()));
    public static final RegistryObject<Item> ITEM_PIPE = ITEMS.register("item_pipe", () -> new BlockItem(ModBlocks.ITEM_PIPE.get(), new Item.Properties()));
    public static final RegistryObject<Item> GAS_PIPE = ITEMS.register("gas_pipe", () -> new BlockItem(ModBlocks.GAS_PIPE.get(), new Item.Properties()));
    public static final RegistryObject<Item> NODE = ITEMS.register("node", () -> new BlockItem(ModBlocks.NODE.get(), new Item.Properties()));
    public static final RegistryObject<Item> OVERCLOCK_UPGRADE = ITEMS.register("overclock_upgrade", OverclockUpgradeItem::new);
    public static final RegistryObject<Item> NETWORK_CARD = ITEMS.register("network_card", NetworkCardItem::new);

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
