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
}
