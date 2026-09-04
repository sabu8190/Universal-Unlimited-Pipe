package com.uup.core.transfer;

import com.uup.config.ModConfig;
import com.uup.core.network.DirectBufferStorage;
import com.uup.logging.UUPLogger;
import net.minecraftforge.energy.IEnergyStorage;

import java.util.List;

public class EnergyTransferExecutor {

    public static long executeTransfer(
            IEnergyStorage sourceHandler,
            List<IEnergyStorage> targetHandlers,
            int overclocks,
            String sourceLabel,
            String targetLabel
    ) {
        if (sourceHandler == null || targetHandlers == null || targetHandlers.isEmpty()) {
            return 0;
        }

        long baseRate = ModConfig.COMMON != null && ModConfig.COMMON.baseEnergyTransferRate != null 
                ? ModConfig.COMMON.baseEnergyTransferRate.get() : Long.MAX_VALUE;
        double multiplier = ModConfig.COMMON != null && ModConfig.COMMON.overclockEnergyMultiplier != null 
                ? ModConfig.COMMON.overclockEnergyMultiplier.get() : 4.0;

        long maxToMove = (long) (baseRate * Math.pow(multiplier, Math.min(overclocks, 16)));
        if (maxToMove <= 0) maxToMove = Long.MAX_VALUE;

        long transferredTotal = 0;

        // Ultra-fast Direct 1-Pass Distribution (Zero allocations, O(Targets))
        for (IEnergyStorage target : targetHandlers) {
            if (target == sourceHandler) continue;
            if (transferredTotal >= maxToMove) break;

            // 1. Check target's immediate intake demand (Simulation)
            int canAccept = target.receiveEnergy(Integer.MAX_VALUE, true);
            if (canAccept <= 0) continue;

            // 2. Limit request by remaining network quota and Integer limit
            int requestAmount = (int) Math.min(canAccept, Math.min(maxToMove - transferredTotal, (long) Integer.MAX_VALUE));
            if (requestAmount <= 0) continue;

            // 3. Extract directly from source
            int extracted = sourceHandler.extractEnergy(requestAmount, false);
            if (extracted <= 0) break; // Source empty or cannot output further

            // 4. Inject directly into target
            int received = target.receiveEnergy(extracted, false);
            transferredTotal += received;

            // Rollback if any unexpected remainder (fail-safe)
            int unaccepted = extracted - received;
            if (unaccepted > 0) {
                sourceHandler.receiveEnergy(unaccepted, false);
            }
        }

        if (transferredTotal > 0) {
            UUPLogger.logTransfer("ENERGY", transferredTotal, sourceLabel, targetLabel);
        }
        return transferredTotal;
    }

    public static void dispatchInternalBuffer(
            DirectBufferStorage storage,
            List<IEnergyStorage> targetHandlers,
            int overclocks
    ) {
        if (storage == null || targetHandlers == null || targetHandlers.isEmpty()) return;
        executeTransfer(storage.getEnergyBuffer(), targetHandlers, overclocks, "UUP_Controller_Energy_Buffer", "Network_Targets");
    }

    public static void ingestToInternalBuffer(
            DirectBufferStorage storage,
            List<IEnergyStorage> sourceHandlers,
            int overclocks
    ) {
        if (storage == null || sourceHandlers == null || sourceHandlers.isEmpty()) return;
        List<IEnergyStorage> target = List.of(storage.getEnergyBuffer());
        for (IEnergyStorage source : sourceHandlers) {
            executeTransfer(source, target, overclocks, "Network_Source", "UUP_Controller_Energy_Buffer");
        }
    }

    public static boolean canConnectStrictEnergy(net.minecraft.world.level.block.entity.BlockEntity be, net.minecraft.core.Direction side) {
        if (!GasTransferExecutor.isMekanismLoaded() || be == null) return false;
        try {
            return MekanismEnergyTransfer.hasStrictEnergyCapability(be, side);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void collectMekanismCapabilities(
            net.minecraft.world.level.block.entity.BlockEntity be,
            net.minecraft.core.Direction side,
            boolean isInsert,
            boolean isExtract,
            List<Object> injectors,
            List<Object> extractors
    ) {
        if (!GasTransferExecutor.isMekanismLoaded() || be == null) return;
        try {
            MekanismEnergyTransfer.collectCapabilities(be, side, isInsert, isExtract, injectors, extractors);
        } catch (Throwable t) {
            UUPLogger.error("Error collecting Mekanism Strict Energy capability: ", t);
        }
    }

    public static void executeMekanismTransfers(List<Object> extractors, List<Object> injectors, int overclocks) {
        if (!GasTransferExecutor.isMekanismLoaded() || extractors == null || injectors == null) return;
        try {
            MekanismEnergyTransfer.processTransfers(extractors, injectors, overclocks);
        } catch (Throwable t) {
            UUPLogger.error("Error executing Mekanism Strict Energy transfers: ", t);
        }
    }

    public static void dispatchMekanismBuffer(Object buffer, List<Object> injectors, int overclocks) {
        if (!GasTransferExecutor.isMekanismLoaded() || buffer == null || injectors == null || injectors.isEmpty()) return;
        try {
            if (buffer instanceof MekanismChemicalTransfer.MekanismBuffer mb) {
                MekanismEnergyTransfer.executeTransfer(mb.energyHandler, injectors, overclocks);
            }
        } catch (Throwable t) {
            UUPLogger.error("Error dispatching Mekanism Energy buffer: ", t);
        }
    }

    public static void ingestMekanismBuffer(Object buffer, List<Object> extractors, int overclocks) {
        if (!GasTransferExecutor.isMekanismLoaded() || buffer == null || extractors == null || extractors.isEmpty()) return;
        try {
            if (buffer instanceof MekanismChemicalTransfer.MekanismBuffer mb) {
                List<Object> target = List.of(mb.energyHandler);
                for (Object ext : extractors) {
                    if (ext instanceof mekanism.api.energy.IStrictEnergyHandler source) {
                        MekanismEnergyTransfer.executeTransfer(source, target, overclocks);
                    }
                }
            }
        } catch (Throwable t) {
            UUPLogger.error("Error ingesting Mekanism Energy buffer: ", t);
        }
    }
}
