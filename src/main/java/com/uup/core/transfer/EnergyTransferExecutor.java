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
        if (sourceHandler == null || targetHandlers.isEmpty()) {
            return 0;
        }

        long baseRate = ModConfig.COMMON != null && ModConfig.COMMON.baseEnergyTransferRate != null 
                ? ModConfig.COMMON.baseEnergyTransferRate.get() : Long.MAX_VALUE;
        double multiplier = ModConfig.COMMON != null && ModConfig.COMMON.overclockEnergyMultiplier != null 
                ? ModConfig.COMMON.overclockEnergyMultiplier.get() : 4.0;

        long maxToMove = (long) (baseRate * Math.pow(multiplier, Math.min(overclocks, 16)));
        if (maxToMove <= 0) maxToMove = Long.MAX_VALUE;

        long transferredTotal = 0;

        while (transferredTotal < maxToMove) {
            int chunkLimit = (int) Math.min(Integer.MAX_VALUE, maxToMove - transferredTotal);
            int extractSim = sourceHandler.extractEnergy(chunkLimit, true);
            if (extractSim <= 0) break;

            int toDistribute = extractSim;
            int acceptedInChunk = 0;

            for (IEnergyStorage target : targetHandlers) {
                if (target == sourceHandler) continue;
                if (toDistribute <= 0) break;
                int received = target.receiveEnergy(toDistribute, false);
                if (received > 0) {
                    acceptedInChunk += received;
                    toDistribute -= received;
                }
            }

            if (acceptedInChunk > 0) {
                sourceHandler.extractEnergy(acceptedInChunk, false);
                transferredTotal += acceptedInChunk;
            } else {
                break;
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
        if (storage == null || targetHandlers.isEmpty()) return;
        executeTransfer(storage.getEnergyBuffer(), targetHandlers, overclocks, "UUP_Controller_Energy_Buffer", "Network_Targets");
    }
}
