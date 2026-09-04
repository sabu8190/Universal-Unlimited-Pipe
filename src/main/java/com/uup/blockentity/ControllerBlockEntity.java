package com.uup.blockentity;

import com.uup.core.network.NetworkController;
import com.uup.core.network.TransferMode;
import com.uup.core.network.TransferNode;
import com.uup.setup.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ControllerBlockEntity extends BlockEntity {

    private final NetworkController network = new NetworkController();

    private final LazyOptional<Object> itemOpt = LazyOptional.of(() -> network.getDirectBuffer().getItemBuffer());
    private final LazyOptional<Object> fluidOpt = LazyOptional.of(() -> network.getDirectBuffer().getFluidBuffer());
    private final LazyOptional<Object> energyOpt = LazyOptional.of(() -> network.getDirectBuffer().getEnergyBuffer());

    public ControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONTROLLER.get(), pos, state);
    }

    public NetworkController getNetwork() {
        return network;
    }

    public void serverTick() {
        if (level instanceof ServerLevel serverLevel) {
            network.tick(serverLevel, worldPosition);
            setChanged();
        }
    }

    public void registerNetworkCard(Player player, ItemStack card) {
        CompoundTag tag = card.getTag();
        if (tag != null && tag.contains("TargetPos")) {
            BlockPos targetPos = BlockPos.of(tag.getLong("TargetPos"));
            TransferNode remoteNode = new TransferNode(
                    targetPos,
                    level.dimension(),
                    Direction.UP,
                    TransferMode.BOTH,
                    0,
                    0,
                    true
            );
            network.addNode(remoteNode);
            setChanged();
            if (!player.getAbilities().instabuild) {
                card.shrink(1);
            }
            player.sendSystemMessage(Component.literal("§a[UUP] Registered wireless target: " + targetPos.toShortString() + " (Consumed 1x Network Upgrade)"));
        } else {
            player.sendSystemMessage(Component.literal("§c[UUP] Network Upgrade has no linked target coordinates!"));
        }
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return itemOpt.cast();
        }
        if (cap == ForgeCapabilities.FLUID_HANDLER) {
            return fluidOpt.cast();
        }
        if (cap == ForgeCapabilities.ENERGY) {
            return energyOpt.cast();
        }
        LazyOptional<T> mekCap = com.uup.core.transfer.GasTransferExecutor.getControllerCapability(network.getDirectBuffer().getMekanismBuffer(), cap);
        if (mekCap.isPresent()) {
            return mekCap;
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        itemOpt.invalidate();
        fluidOpt.invalidate();
        energyOpt.invalidate();
        network.getDirectBuffer().invalidate();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Network", network.serializeNBT());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Network")) {
            network.deserializeNBT(tag.getCompound("Network"));
        }
    }
}
