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

        // Step 1: Calculate total demand from valid targets in a single pass (Simulation)
        long totalDemand = 0;
        for (IEnergyStorage target : targetHandlers) {
            if (target == sourceHandler) continue;
            int demand = target.receiveEnergy(Integer.MAX_VALUE, true);
            if (demand > 0) {
                totalDemand += demand;
            }
        }

        if (totalDemand <= 0) {
            return 0;
        }

        // Limit requested extraction by network max rate and integer limit
        int extractRequest = (int) Math.min(totalDemand, Math.min(maxToMove, (long) Integer.MAX_VALUE));
        if (extractRequest <= 0) {
            return 0;
        }

        // Step 2: Check how much source can actually provide (Simulation)
        int extractSim = sourceHandler.extractEnergy(extractRequest, true);
        if (extractSim <= 0) {
            return 0;
        }

        // Step 3: Extract from source (Execution)
        int actuallyExtracted = sourceHandler.extractEnergy(extractSim, false);
        if (actuallyExtracted <= 0) {
            return 0;
        }

        // Step 4: Distribute extracted energy to targets in a single pass (Execution)
        int remaining = actuallyExtracted;
        for (IEnergyStorage target : targetHandlers) {
            if (target == sourceHandler) continue;
            if (remaining <= 0) break;

            int received = target.receiveEnergy(remaining, false);
            if (received > 0) {
                remaining -= received;
            }
        }

        // If any energy could not be accepted (rare edge case), return it to source
        if (remaining > 0) {
            sourceHandler.receiveEnergy(remaining, false);
        }

        long transferredTotal = actuallyExtracted - remaining;
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
}
