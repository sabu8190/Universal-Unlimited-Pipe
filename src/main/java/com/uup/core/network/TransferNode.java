package com.uup.core.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Objects;

public class TransferNode {

    private final BlockPos pos;
    private final ResourceKey<Level> dimension;
    private final Direction targetSide;
    private TransferMode mode;
    private int priority;
    private int channelId;
    private int overclocks;
    private boolean isWirelessRemote;

    public TransferNode(BlockPos pos, ResourceKey<Level> dimension, Direction targetSide, TransferMode mode, int priority, int channelId, int overclocks, boolean isWirelessRemote) {
        this.pos = pos;
        this.dimension = dimension;
        this.targetSide = targetSide;
        this.mode = mode;
        this.priority = priority;
        this.channelId = channelId;
        this.overclocks = overclocks;
        this.isWirelessRemote = isWirelessRemote;
    }

    public TransferNode(BlockPos pos, ResourceKey<Level> dimension, Direction targetSide, TransferMode mode, int priority, int channelId, boolean isWirelessRemote) {
        this(pos, dimension, targetSide, mode, priority, channelId, 0, isWirelessRemote);
    }

    public BlockPos getPos() {
        return pos;
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    public Direction getTargetSide() {
        return targetSide;
    }

    public TransferMode getMode() {
        return mode;
    }

    public void setMode(TransferMode mode) {
        this.mode = mode;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public int getChannelId() {
        return channelId;
    }

    public void setChannelId(int channelId) {
        this.channelId = channelId;
    }

    public int getOverclocks() {
        return overclocks;
    }

    public void setOverclocks(int overclocks) {
        this.overclocks = overclocks;
    }

    public boolean isWirelessRemote() {
        return isWirelessRemote;
    }

    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Pos", pos.asLong());
        tag.putString("Dim", dimension.location().toString());
        tag.putInt("Side", targetSide.get3DDataValue());
        tag.putString("Mode", mode.name());
        tag.putInt("Priority", priority);
        tag.putInt("Channel", channelId);
        tag.putInt("Overclocks", overclocks);
        tag.putBoolean("Wireless", isWirelessRemote);
        return tag;
    }

    public static TransferNode deserializeNBT(CompoundTag tag) {
        BlockPos pos = BlockPos.of(tag.getLong("Pos"));
        ResourceLocation dimLoc = new ResourceLocation(tag.getString("Dim"));
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, dimLoc);
        Direction side = Direction.from3DDataValue(tag.getInt("Side"));
        TransferMode mode = TransferMode.valueOf(tag.getString("Mode"));
        int priority = tag.getInt("Priority");
        int channel = tag.getInt("Channel");
        int overclocks = tag.contains("Overclocks") ? tag.getInt("Overclocks") : 0;
        boolean wireless = tag.getBoolean("Wireless");
        return new TransferNode(pos, dim, side, mode, priority, channel, overclocks, wireless);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransferNode that = (TransferNode) o;
        return Objects.equals(pos, that.pos) && Objects.equals(dimension, that.dimension) && targetSide == that.targetSide;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pos, dimension, targetSide);
    }
}
