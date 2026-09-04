package com.uup.gui;

import com.uup.core.network.TransferMode;
import com.uup.item.OverclockUpgradeItem;
import com.uup.setup.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

public class NodeMenu extends AbstractContainerMenu {

    private final ContainerData data;
    private final BlockPos pos;
    private final Direction side;
    private final BlockEntity blockEntity;
    private final IItemHandler upgradeHandler;

    // Client-side constructor
    public NodeMenu(int containerId, Inventory playerInv, FriendlyByteBuf extraData) {
        this(containerId, playerInv, extraData.readBlockPos(), extraData.readEnum(Direction.class),
                new ItemStackHandler(1), new SimpleContainerData(3));
    }

    // Server-side constructor
    public NodeMenu(int containerId, Inventory playerInv, BlockPos pos, Direction side,
                    IItemHandler upgradeHandler, ContainerData data) {
        super(ModMenus.NODE_MENU.get(), containerId);
        this.pos = pos;
        this.side = side;
        this.blockEntity = playerInv.player.level().getBlockEntity(pos);
        this.upgradeHandler = upgradeHandler;
        this.data = data;

        checkContainerDataCount(data, 3);
        addDataSlots(data);

        // Upgrade Slot (Index 0)
        this.addSlot(new SlotItemHandler(upgradeHandler, 0, 80, 36) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof OverclockUpgradeItem;
            }

            @Override
            public int getMaxStackSize() {
                return 16;
            }
        });

        // Player Inventory (27 slots)
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }

        // Player Hotbar (9 slots)
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, 142));
        }
    }

    public int getPriority() {
        return data.get(0);
    }

    public TransferMode getMode() {
        int ord = data.get(1);
        TransferMode[] vals = TransferMode.values();
        if (ord >= 0 && ord < vals.length) {
            return vals[ord];
        }
        return TransferMode.EXTRACT;
    }

    public int getChannelId() {
        return data.get(2);
    }

    public BlockPos getPos() {
        return pos;
    }

    public Direction getSide() {
        return side;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        switch (id) {
            case 0 -> data.set(0, data.get(0) - 10);
            case 1 -> data.set(0, data.get(0) - 1);
            case 2 -> data.set(0, data.get(0) + 1);
            case 3 -> data.set(0, data.get(0) + 10);
            case 4 -> {
                int nextOrd = (data.get(1) + 1) % TransferMode.values().length;
                data.set(1, nextOrd);
            }
            case 5 -> {
                int nextCh = (data.get(2) + 1) % 16;
                data.set(2, nextCh);
            }
            default -> {
                return false;
            }
        }
        if (blockEntity != null) {
            blockEntity.setChanged();
        }
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stackInSlot = slot.getItem();
            itemstack = stackInSlot.copy();

            if (index == 0) {
                // From upgrade slot to player inv
                if (!this.moveItemStackTo(stackInSlot, 1, 37, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // From player inv to upgrade slot if it's an OverclockUpgradeItem
                if (stackInSlot.getItem() instanceof OverclockUpgradeItem) {
                    if (!this.moveItemStackTo(stackInSlot, 0, 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (index >= 1 && index < 28) {
                    if (!this.moveItemStackTo(stackInSlot, 28, 37, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (index >= 28 && index < 37 && !this.moveItemStackTo(stackInSlot, 1, 28, false)) {
                    return ItemStack.EMPTY;
                }
            }

            if (stackInSlot.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            if (stackInSlot.getCount() == itemstack.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(player, stackInSlot);
        }
        return itemstack;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(ContainerLevelAccess.create(player.level(), pos), player, player.level().getBlockState(pos).getBlock());
    }
}
