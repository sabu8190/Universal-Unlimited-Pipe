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
        int tanks = sourceHandler.getTanks();

        if (tanks > 0) {
            for (int tank = 0; tank < tanks && filledTotal < maxToMove; tank++) {
                FluidStack inTank = sourceHandler.getFluidInTank(tank);
                if (inTank.isEmpty()) continue;

                for (IFluidHandler target : targetHandlers) {
                    if (target == sourceHandler) continue;
                    if (filledTotal >= maxToMove) break;

                    int queryLimit = (int) Math.min((long) inTank.getAmount(), maxToMove - filledTotal);
                    if (queryLimit <= 0) break;

                    // 1. Simulate target intake capacity
                    FluidStack sample = inTank.copy();
                    sample.setAmount(queryLimit);
                    int canAccept = target.fill(sample, IFluidHandler.FluidAction.SIMULATE);
                    if (canAccept <= 0) continue;

                    // 2. Extract from source
                    FluidStack toDrain = inTank.copy();
                    toDrain.setAmount(canAccept);
                    FluidStack actuallyExtracted = sourceHandler.drain(toDrain, IFluidHandler.FluidAction.EXECUTE);
                    if (actuallyExtracted.isEmpty()) {
                        actuallyExtracted = sourceHandler.drain(canAccept, IFluidHandler.FluidAction.EXECUTE);
                    }
                    if (actuallyExtracted.isEmpty()) break;

                    // 3. Inject into target
                    int accepted = target.fill(actuallyExtracted, IFluidHandler.FluidAction.EXECUTE);
                    filledTotal += accepted;

                    // Rollback remainder if any
                    int unaccepted = actuallyExtracted.getAmount() - accepted;
                    if (unaccepted > 0) {
                        actuallyExtracted.setAmount(unaccepted);
                        sourceHandler.fill(actuallyExtracted, IFluidHandler.FluidAction.EXECUTE);
                    }
                }
            }
        } else {
            // Fallback for fluid handlers where getTanks() returns 0
            for (IFluidHandler target : targetHandlers) {
                if (target == sourceHandler) continue;
                if (filledTotal >= maxToMove) break;

                int queryLimit = (int) Math.min((long) Integer.MAX_VALUE, maxToMove - filledTotal);
                FluidStack sample = sourceHandler.drain(queryLimit, IFluidHandler.FluidAction.SIMULATE);
                if (sample.isEmpty()) break;

                int canAccept = target.fill(sample, IFluidHandler.FluidAction.SIMULATE);
                if (canAccept <= 0) continue;

                FluidStack actuallyExtracted = sourceHandler.drain(canAccept, IFluidHandler.FluidAction.EXECUTE);
                if (actuallyExtracted.isEmpty()) break;

                int accepted = target.fill(actuallyExtracted, IFluidHandler.FluidAction.EXECUTE);
                filledTotal += accepted;

                int unaccepted = actuallyExtracted.getAmount() - accepted;
                if (unaccepted > 0) {
                    actuallyExtracted.setAmount(unaccepted);
                    sourceHandler.fill(actuallyExtracted, IFluidHandler.FluidAction.EXECUTE);
                }
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
