package com.uup.core.transfer;

import com.uup.logging.UUPLogger;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.ModList;

import java.util.List;

public class GasTransferExecutor {

    private static final boolean MEKANISM_LOADED = ModList.get().isLoaded("mekanism");

    public static boolean isMekanismLoaded() {
        return MEKANISM_LOADED;
    }

    public static boolean canConnectGas(BlockEntity be, Direction side) {
        if (!MEKANISM_LOADED || be == null) return false;
        try {
            return MekanismChemicalTransfer.hasChemicalCapability(be, side);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void collectCapabilities(
            BlockEntity be,
            Direction side,
            boolean isInsert,
            boolean isExtract,
            List<Object> gasInj, List<Object> gasExt,
            List<Object> infInj, List<Object> infExt,
            List<Object> pigInj, List<Object> pigExt,
            List<Object> sluInj, List<Object> sluExt
    ) {
        if (!MEKANISM_LOADED || be == null) return;
        try {
            MekanismChemicalTransfer.collectCapabilities(be, side, isInsert, isExtract,
                    gasInj, gasExt, infInj, infExt, pigInj, pigExt, sluInj, sluExt);
        } catch (Throwable t) {
            UUPLogger.error("Error collecting Mekanism capabilities: ", t);
        }
    }

    public static void executeAllTransfers(
            List<Object> gasInj, List<Object> gasExt,
            List<Object> infInj, List<Object> infExt,
            List<Object> pigInj, List<Object> pigExt,
            List<Object> sluInj, List<Object> sluExt,
            int overclocks
    ) {
        if (!MEKANISM_LOADED) return;
        try {
            MekanismChemicalTransfer.processTransfers(gasExt, gasInj, infExt, infInj, pigExt, pigInj, sluExt, sluInj, overclocks);
        } catch (Throwable t) {
            UUPLogger.error("Error executing Mekanism transfers: ", t);
        }
    }

    public static Object createMekanismBuffer() {
        if (!MEKANISM_LOADED) return null;
        try {
            return new MekanismChemicalTransfer.MekanismBuffer();
        } catch (Throwable t) {
            return null;
        }
    }

    public static void dispatchInternalBuffer(
            Object buffer,
            List<Object> gasInj, List<Object> infInj, List<Object> pigInj, List<Object> sluInj,
            int overclocks
    ) {
        if (!MEKANISM_LOADED || buffer == null) return;
        try {
            MekanismChemicalTransfer.dispatchInternalBuffer((MekanismChemicalTransfer.MekanismBuffer) buffer, gasInj, infInj, pigInj, sluInj, overclocks);
        } catch (Throwable t) {
            UUPLogger.error("Error dispatching Mekanism buffer: ", t);
        }
    }

    public static void ingestToInternalBuffer(
            Object buffer,
            List<Object> gasExt, List<Object> infExt, List<Object> pigExt, List<Object> sluExt,
            int overclocks
    ) {
        if (!MEKANISM_LOADED || buffer == null) return;
        try {
            MekanismChemicalTransfer.ingestToInternalBuffer((MekanismChemicalTransfer.MekanismBuffer) buffer, gasExt, infExt, pigExt, sluExt, overclocks);
        } catch (Throwable t) {
            UUPLogger.error("Error ingesting to Mekanism buffer: ", t);
        }
    }

    public static <T> net.minecraftforge.common.util.LazyOptional<T> getControllerCapability(Object buffer, net.minecraftforge.common.capabilities.Capability<T> cap) {
        if (!MEKANISM_LOADED || buffer == null) return net.minecraftforge.common.util.LazyOptional.empty();
        try {
            return ((MekanismChemicalTransfer.MekanismBuffer) buffer).getCapability(cap);
        } catch (Throwable t) {
            return net.minecraftforge.common.util.LazyOptional.empty();
        }
    }

    public static void invalidateMekanismBuffer(Object buffer) {
        if (!MEKANISM_LOADED || buffer == null) return;
        try {
            ((MekanismChemicalTransfer.MekanismBuffer) buffer).invalidate();
        } catch (Throwable t) {
            // ignore
        }
    }

    public static net.minecraft.nbt.CompoundTag serializeMekanismBuffer(Object buffer) {
        if (!MEKANISM_LOADED || buffer == null) return new net.minecraft.nbt.CompoundTag();
        try {
            return ((MekanismChemicalTransfer.MekanismBuffer) buffer).serializeNBT();
        } catch (Throwable t) {
            return new net.minecraft.nbt.CompoundTag();
        }
    }

    public static void deserializeMekanismBuffer(Object buffer, net.minecraft.nbt.CompoundTag tag) {
        if (!MEKANISM_LOADED || buffer == null || tag == null) return;
        try {
            ((MekanismChemicalTransfer.MekanismBuffer) buffer).deserializeNBT(tag);
        } catch (Throwable t) {
            // ignore
        }
    }
}
