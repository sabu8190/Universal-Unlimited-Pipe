package com.uup.core.network;

import com.uup.core.transfer.GasTransferExecutor;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.ItemStackHandler;

public class DirectBufferStorage implements INBTSerializable<CompoundTag> {

    public static final int ITEM_SLOT_COUNT = 108;
    public static final int MAX_FLUID_CAPACITY = Integer.MAX_VALUE;
    public static final int MAX_ENERGY_CAPACITY = Integer.MAX_VALUE;

    private final ItemStackHandler itemBuffer;
    private final FluidTank fluidBuffer;
    private final EnergyStorage energyBuffer;
    private final Object mekanismBuffer;

    public DirectBufferStorage() {
        this.itemBuffer = new ItemStackHandler(ITEM_SLOT_COUNT) {
            @Override
            public int getSlotLimit(int slot) {
                return Integer.MAX_VALUE;
            }
        };
        this.fluidBuffer = new FluidTank(MAX_FLUID_CAPACITY);
        this.energyBuffer = new EnergyStorage(MAX_ENERGY_CAPACITY, MAX_ENERGY_CAPACITY, MAX_ENERGY_CAPACITY);
        this.mekanismBuffer = GasTransferExecutor.createMekanismBuffer();
    }

    public ItemStackHandler getItemBuffer() {
        return itemBuffer;
    }

    public FluidTank getFluidBuffer() {
        return fluidBuffer;
    }

    public EnergyStorage getEnergyBuffer() {
        return energyBuffer;
    }

    public Object getMekanismBuffer() {
        return mekanismBuffer;
    }

    public void invalidate() {
        GasTransferExecutor.invalidateMekanismBuffer(mekanismBuffer);
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.put("Items", itemBuffer.serializeNBT());
        tag.put("Fluids", fluidBuffer.writeToNBT(new CompoundTag()));
        tag.put("Energy", energyBuffer.serializeNBT());
        if (mekanismBuffer != null) {
            tag.put("Mekanism", GasTransferExecutor.serializeMekanismBuffer(mekanismBuffer));
        }
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        if (nbt.contains("Items")) {
            itemBuffer.deserializeNBT(nbt.getCompound("Items"));
        }
        if (nbt.contains("Fluids")) {
            fluidBuffer.readFromNBT(nbt.getCompound("Fluids"));
        }
        if (nbt.contains("Energy")) {
            energyBuffer.deserializeNBT(nbt.get("Energy"));
        }
        if (nbt.contains("Mekanism") && mekanismBuffer != null) {
            GasTransferExecutor.deserializeMekanismBuffer(mekanismBuffer, nbt.getCompound("Mekanism"));
        }
    }
}
