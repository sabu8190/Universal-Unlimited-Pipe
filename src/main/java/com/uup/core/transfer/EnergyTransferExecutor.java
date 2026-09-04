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
}
