package com.uup.core.transfer;

import com.uup.config.ModConfig;
import com.uup.core.network.DirectBufferStorage;
import com.uup.logging.UUPLogger;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class FluidTransferExecutor {

    public static void executeAllFluidTransfers(
            List<IFluidHandler> extractors,
            List<IFluidHandler> injectors,
            int overclocks
    ) {
        executeAllFluidTransfers(extractors, injectors, overclocks, 0L, null);
    }

    public static void executeAllFluidTransfers(
            List<IFluidHandler> extractors,
            List<IFluidHandler> injectors,
            int overclocks,
            long currentTick,
            @Nullable Map<IFluidHandler, Map<Fluid, Long>> persistentFluidCache
    ) {
        if (extractors == null || extractors.isEmpty() || injectors == null || injectors.isEmpty()) {
            return;
        }

        Map<IFluidHandler, Set<Fluid>> sharedRejectedMap = new IdentityHashMap<>();
        Set<IFluidHandler> receivedInThisTick = Collections.newSetFromMap(new IdentityHashMap<>());

        for (IFluidHandler extractor : extractors) {
            if (extractor == null) continue;
            if (receivedInThisTick.contains(extractor)) continue;

            executeTransfer(
                    extractor,
                    injectors,
                    overclocks,
                    "UUP_Fluid_Extract",
                    "UUP_Fluid_Insert",
                    sharedRejectedMap,
                    receivedInThisTick
            );
        }
    }

    public static long executeTransfer(
            IFluidHandler sourceHandler,
            List<IFluidHandler> targetHandlers,
            int overclocks,
            String sourceLabel,
            String targetLabel
    ) {
        return executeTransfer(sourceHandler, targetHandlers, overclocks, sourceLabel, targetLabel, null, null, 0L, null);
    }



    public static long executeTransfer(
            IFluidHandler sourceHandler,
            List<IFluidHandler> targetHandlers,
            int overclocks,
            String sourceLabel,
            String targetLabel,
            @Nullable Map<IFluidHandler, Set<Fluid>> sharedRejectedMap,
            @Nullable Set<IFluidHandler> receivedHandlers,
            long currentTick,
            @Nullable Map<IFluidHandler, Map<Fluid, Long>> persistentCache
    ) {
        return executeTransfer(sourceHandler, targetHandlers, overclocks, sourceLabel, targetLabel, sharedRejectedMap, receivedHandlers);
    }

    public static long executeTransfer(
            IFluidHandler sourceHandler,
            List<IFluidHandler> targetHandlers,
            int overclocks,
            String sourceLabel,
            String targetLabel,
            @Nullable Map<IFluidHandler, Set<Fluid>> sharedRejectedMap,
            @Nullable Set<IFluidHandler> receivedHandlers
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

        Map<IFluidHandler, Set<Fluid>> rejectedMap = sharedRejectedMap != null 
                ? sharedRejectedMap : new IdentityHashMap<>();

        List<IFluidHandler> validTargets = new ArrayList<>(targetHandlers.size());
        for (IFluidHandler target : targetHandlers) {
            if (target != null && target != sourceHandler) {
                validTargets.add(target);
            }
        }
        if (validTargets.isEmpty()) return 0;

        if (tanks > 0) {
            for (int tank = 0; tank < tanks && filledTotal < maxToMove; tank++) {
                FluidStack inTank = sourceHandler.getFluidInTank(tank);
                if (inTank.isEmpty()) continue;
                Fluid fluid = inTank.getFluid();

                for (IFluidHandler target : validTargets) {
                    if (filledTotal >= maxToMove) break;

                    Set<Fluid> rejected = rejectedMap.get(target);
                    if (rejected != null && rejected.contains(fluid)) {
                        continue;
                    }

                    int queryLimit = (int) Math.min((long) inTank.getAmount(), maxToMove - filledTotal);
                    if (queryLimit <= 0) break;

                    // 1. Simulate target intake capacity
                    FluidStack sample = inTank.copy();
                    sample.setAmount(queryLimit);
                    int canAccept = target.fill(sample, IFluidHandler.FluidAction.SIMULATE);
                    if (canAccept <= 0) {
                        rejectedMap.computeIfAbsent(target, k -> new HashSet<>()).add(fluid);
                        continue;
                    }

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

                    if (accepted > 0) {
                        if (receivedHandlers != null) {
                            receivedHandlers.add(target);
                        }
                    }

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
            for (IFluidHandler target : validTargets) {
                if (filledTotal >= maxToMove) break;

                int queryLimit = (int) Math.min((long) Integer.MAX_VALUE, maxToMove - filledTotal);
                FluidStack sample = sourceHandler.drain(queryLimit, IFluidHandler.FluidAction.SIMULATE);
                if (sample.isEmpty()) break;
                Fluid fluid = sample.getFluid();

                Set<Fluid> rejected = rejectedMap.get(target);
                if (rejected != null && rejected.contains(fluid)) {
                    continue;
                }

                int canAccept = target.fill(sample, IFluidHandler.FluidAction.SIMULATE);
                if (canAccept <= 0) {
                    rejectedMap.computeIfAbsent(target, k -> new HashSet<>()).add(fluid);
                    continue;
                }

                FluidStack actuallyExtracted = sourceHandler.drain(canAccept, IFluidHandler.FluidAction.EXECUTE);
                if (actuallyExtracted.isEmpty()) break;

                int accepted = target.fill(actuallyExtracted, IFluidHandler.FluidAction.EXECUTE);
                filledTotal += accepted;

                if (accepted > 0) {
                    if (receivedHandlers != null) {
                        receivedHandlers.add(target);
                    }
                }

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
