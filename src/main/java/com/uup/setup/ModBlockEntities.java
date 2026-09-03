package com.uup.setup;

import com.uup.UniversalUnlimitedPipe;
import com.uup.blockentity.ControllerBlockEntity;
import com.uup.blockentity.NodeBlockEntity;
import com.uup.blockentity.PipeBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, UniversalUnlimitedPipe.MODID);

    public static final RegistryObject<BlockEntityType<ControllerBlockEntity>> CONTROLLER = BLOCK_ENTITIES.register("controller",
            () -> BlockEntityType.Builder.of(ControllerBlockEntity::new, ModBlocks.CONTROLLER.get()).build(null));

    public static final RegistryObject<BlockEntityType<PipeBlockEntity>> PIPE = BLOCK_ENTITIES.register("pipe",
            () -> BlockEntityType.Builder.of(PipeBlockEntity::new,
                    ModBlocks.PIPE.get(),
                    ModBlocks.ENERGY_PIPE.get(),
                    ModBlocks.FLUID_PIPE.get(),
                    ModBlocks.ITEM_PIPE.get(),
                    ModBlocks.GAS_PIPE.get()).build(null));

    public static final RegistryObject<BlockEntityType<NodeBlockEntity>> NODE = BLOCK_ENTITIES.register("node",
            () -> BlockEntityType.Builder.of(NodeBlockEntity::new, ModBlocks.NODE.get()).build(null));

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }
}
