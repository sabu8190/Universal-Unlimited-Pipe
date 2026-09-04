package com.uup.blockentity;

import com.uup.block.NodeBlock;
import com.uup.core.network.TransferMode;
import com.uup.core.network.TransferNode;
import com.uup.gui.NodeMenu;
import com.uup.item.OverclockUpgradeItem;
import com.uup.setup.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class NodeBlockEntity extends BlockEntity implements MenuProvider {

    private TransferMode mode = TransferMode.BOTH;
    private int priority = 0;
    private int channelId = 0;

    private final ItemStackHandler upgradeHandler = new ItemStackHandler(1) {
        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return stack.getItem() instanceof OverclockUpgradeItem;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 16;
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case 0 -> priority;
                case 1 -> mode.ordinal();
                case 2 -> channelId;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            switch (index) {
                case 0 -> priority = value;
                case 1 -> {
                    TransferMode[] vals = TransferMode.values();
                    if (value >= 0 && value < vals.length) {
                        mode = vals[value];
                    }
                }
                case 2 -> channelId = value;
            }
            setChanged();
        }

        @Override
        public int getCount() {
            return 3;
        }
    };

    public NodeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NODE.get(), pos, state);
    }

    public Direction getAttachedFacing() {
        if (getBlockState().hasProperty(NodeBlock.FACING)) {
            return getBlockState().getValue(NodeBlock.FACING);
        }
        return Direction.UP;
    }

    public ItemStackHandler getUpgradeHandler() {
        return upgradeHandler;
    }

    public TransferNode toNodeData() {
        int overclocks = upgradeHandler.getStackInSlot(0).getCount();
        return new TransferNode(
                worldPosition,
                level != null ? level.dimension() : Level.OVERWORLD,
                getAttachedFacing(),
                mode,
                priority,
                channelId,
                overclocks,
                false
        );
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.uup.node");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return new NodeMenu(containerId, playerInv, worldPosition, getAttachedFacing(), upgradeHandler, data);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Mode", mode.name());
        tag.putInt("Priority", priority);
        tag.putInt("Channel", channelId);
        tag.put("Upgrades", upgradeHandler.serializeNBT());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Mode")) {
            mode = TransferMode.valueOf(tag.getString("Mode"));
        }
        if (tag.contains("Priority")) {
            priority = tag.getInt("Priority");
        }
        if (tag.contains("Channel")) {
            channelId = tag.getInt("Channel");
        }
        if (tag.contains("Upgrades")) {
            upgradeHandler.deserializeNBT(tag.getCompound("Upgrades"));
        }
    }
}
