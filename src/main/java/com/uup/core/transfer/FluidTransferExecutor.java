package com.uup.core.transfer;

import com.uup.config.ModConfig;
import com.uup.core.network.DirectBufferStorage;
import com.uup.logging.UUPLogger;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

import java.util.List;

public class FluidTransferExecutor {

    public static long executeTransfer(
            IFluidHandler sourceHandler,
            List<IFluidHandler> targetHandlers,
            int overclocks,
            String sourceLabel,
            String targetLabel
    ) {
        if (sourceHandler == null || targetHandlers == null || targetHandlers.isEmpty()) {
            return 0;
        }

        int baseRate = ModConfig.COMMON != null && ModConfig.COMMON.baseFluidTransferRate != null 
                ? ModConfig.COMMON.baseFluidTransferRate.get() : Integer.MAX_VALUE;
        double multiplier = ModConfig.COMMON != null && ModConfig.COMMON.overclockFluidMultiplier != null 
                ? ModConfig.COMMON.overclockFluidMultiplier.get() : 4.0;

        long maxToMove = (long) (baseRate * Math.pow(multiplier, Math.min(overclocks, 16)));
        if (maxToMove <= 0) maxToMove = Integer.MAX_VALUE;

        long filledTotal = 0;

        // Ultra-fast Direct 1-Pass Distribution (Zero allocations, O(Targets))
        for (IFluidHandler target : targetHandlers) {
            if (target == sourceHandler) continue;
            if (filledTotal >= maxToMove) break;

            // 1. Simulate draining a sample from source to know fluid type
            int queryLimit = (int) Math.min(Integer.MAX_VALUE, maxToMove - filledTotal);
            FluidStack sample = sourceHandler.drain(queryLimit, IFluidHandler.FluidAction.SIMULATE);
            if (sample.isEmpty()) break; // Source empty

            // 2. Check target's immediate intake capacity (Simulation)
            int canAccept = target.fill(sample, IFluidHandler.FluidAction.SIMULATE);
            if (canAccept <= 0) continue;

            // 3. Extract exact accepted amount from source (Execution)
            FluidStack actuallyExtracted = sourceHandler.drain(canAccept, IFluidHandler.FluidAction.EXECUTE);
            if (actuallyExtracted.isEmpty()) break;

            // 4. Inject into target (Execution)
            int accepted = target.fill(actuallyExtracted, IFluidHandler.FluidAction.EXECUTE);
            filledTotal += accepted;

            // Rollback if any unexpected remainder (fail-safe)
            int unaccepted = actuallyExtracted.getAmount() - accepted;
            if (unaccepted > 0) {
                actuallyExtracted.setAmount(unaccepted);
                sourceHandler.fill(actuallyExtracted, IFluidHandler.FluidAction.EXECUTE);
            }
        }

        if (filledTotal > 0) {
            UUPLogger.logTransfer("FLUID", filledTotal, sourceLabel, targetLabel);
        }
        return filledTotal;
    }

    public static void dispatchInternalBuffer(
            DirectBufferStorage storage,
            List<IFluidHandler> targetHandlers,
            int overclocks
    ) {
        if (storage == null || targetHandlers == null || targetHandlers.isEmpty()) return;
        executeTransfer(storage.getFluidBuffer(), targetHandlers, overclocks, "UUP_Controller_Fluid_Buffer", "Network_Targets");
    }

    public static void ingestToInternalBuffer(
            DirectBufferStorage storage,
            List<IFluidHandler> sourceHandlers,
            int overclocks
    ) {
        if (storage == null || sourceHandlers == null || sourceHandlers.isEmpty()) return;
        List<IFluidHandler> target = List.of(storage.getFluidBuffer());
        for (IFluidHandler source : sourceHandlers) {
            executeTransfer(source, target, overclocks, "Network_Source", "UUP_Controller_Fluid_Buffer");
        }
    }
}
