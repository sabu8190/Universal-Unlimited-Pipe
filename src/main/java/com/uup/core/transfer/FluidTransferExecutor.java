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
        if (sourceHandler == null || targetHandlers.isEmpty()) {
            return 0;
        }

        int baseRate = ModConfig.COMMON != null && ModConfig.COMMON.baseFluidTransferRate != null 
                ? ModConfig.COMMON.baseFluidTransferRate.get() : Integer.MAX_VALUE;
        double multiplier = ModConfig.COMMON != null && ModConfig.COMMON.overclockFluidMultiplier != null 
                ? ModConfig.COMMON.overclockFluidMultiplier.get() : 4.0;

        long maxToMove = (long) (baseRate * Math.pow(multiplier, Math.min(overclocks, 16)));
        if (maxToMove <= 0) maxToMove = Integer.MAX_VALUE;
        int maxInt = (int) Math.min(maxToMove, Integer.MAX_VALUE);

        FluidStack simulatedDrain = sourceHandler.drain(maxInt, IFluidHandler.FluidAction.SIMULATE);
        if (simulatedDrain.isEmpty()) return 0;

        FluidStack toInsert = simulatedDrain.copy();
        int filledTotal = 0;

        for (IFluidHandler target : targetHandlers) {
            if (target == sourceHandler) continue;
            if (toInsert.isEmpty()) break;
            int filled = target.fill(toInsert, IFluidHandler.FluidAction.EXECUTE);
            if (filled > 0) {
                filledTotal += filled;
                toInsert.shrink(filled);
            }
        }

        if (filledTotal > 0) {
            sourceHandler.drain(filledTotal, IFluidHandler.FluidAction.EXECUTE);
            UUPLogger.logTransfer("FLUID", filledTotal, sourceLabel, targetLabel);
        }
        return filledTotal;
    }

    public static void dispatchInternalBuffer(
            DirectBufferStorage storage,
            List<IFluidHandler> targetHandlers,
            int overclocks
    ) {
        if (storage == null || targetHandlers.isEmpty()) return;
        executeTransfer(storage.getFluidBuffer(), targetHandlers, overclocks, "UUP_Controller_Fluid_Buffer", "Network_Targets");
    }
}
