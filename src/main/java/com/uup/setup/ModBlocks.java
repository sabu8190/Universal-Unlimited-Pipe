package com.uup.setup;

import com.uup.UniversalUnlimitedPipe;
import com.uup.block.ControllerBlock;
import com.uup.block.NodeBlock;
import com.uup.block.PipeBlock;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, UniversalUnlimitedPipe.MODID);

    public static final RegistryObject<Block> CONTROLLER = BLOCKS.register("controller", ControllerBlock::new);
    public static final RegistryObject<Block> PIPE = BLOCKS.register("pipe", () -> new PipeBlock(PipeBlock.PipeType.UNIVERSAL));
    public static final RegistryObject<Block> ENERGY_PIPE = BLOCKS.register("energy_pipe", () -> new PipeBlock(PipeBlock.PipeType.ENERGY));
    public static final RegistryObject<Block> FLUID_PIPE = BLOCKS.register("fluid_pipe", () -> new PipeBlock(PipeBlock.PipeType.FLUID));
    public static final RegistryObject<Block> ITEM_PIPE = BLOCKS.register("item_pipe", () -> new PipeBlock(PipeBlock.PipeType.ITEM));
    public static final RegistryObject<Block> GAS_PIPE = BLOCKS.register("gas_pipe", () -> new PipeBlock(PipeBlock.PipeType.GAS));
    public static final RegistryObject<Block> NODE = BLOCKS.register("node", NodeBlock::new);

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
    }
}
