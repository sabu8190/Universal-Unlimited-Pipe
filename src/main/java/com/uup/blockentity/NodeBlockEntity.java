package com.uup.blockentity;

import com.uup.block.NodeBlock;
import com.uup.core.network.TransferMode;
import com.uup.core.network.TransferNode;
import com.uup.setup.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class NodeBlockEntity extends BlockEntity {

    private TransferMode mode = TransferMode.EXTRACT;
    private int priority = 0;
    private int channelId = 0;

    public NodeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NODE.get(), pos, state);
    }

    public Direction getAttachedFacing() {
        if (getBlockState().hasProperty(NodeBlock.FACING)) {
            return getBlockState().getValue(NodeBlock.FACING);
        }
        return Direction.UP;
    }

    public TransferNode toNodeData() {
        return new TransferNode(
                worldPosition,
                level != null ? level.dimension() : Level.OVERWORLD,
                getAttachedFacing(),
                mode,
                priority,
                channelId,
                false
        );
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Mode", mode.name());
        tag.putInt("Priority", priority);
        tag.putInt("Channel", channelId);
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
    }
}
