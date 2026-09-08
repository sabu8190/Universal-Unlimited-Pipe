package com.uup.core.transfer;

import com.uup.config.ModConfig;
import com.uup.core.network.DirectBufferStorage;
import com.uup.logging.UUPLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class EnergyTransferExecutor {

    public static void executeAllEnergyTransfers(
            List<IEnergyStorage> extractors,
            List<IEnergyStorage> injectors,
            int overclocks
    ) {
        executeAllEnergyTransfers(extractors, injectors, overclocks, 0L, null);
    }

    public static void executeAllEnergyTransfers(
            List<IEnergyStorage> extractors,
            List<IEnergyStorage> injectors,
            int overclocks,
            long currentTick,
            @Nullable java.util.Map<IEnergyStorage, Long> persistentEnergyCache
    ) {
        executeAllEnergyTransfers(extractors, injectors, overclocks, currentTick, persistentEnergyCache, null, null);
    }

    public static void executeAllEnergyTransfers(
            List<IEnergyStorage> extractors,
            List<IEnergyStorage> injectors,
            int overclocks,
            long currentTick,
            @Nullable java.util.Map<IEnergyStorage, Long> persistentEnergyCache,
            @Nullable Map<Object, BlockPos> handlerPositions,
            @Nullable Map<Object, Integer> handlerPriorities
    ) {
        executeAllEnergyTransfers(extractors, injectors, overclocks, currentTick, persistentEnergyCache, handlerPositions, handlerPriorities, null);
    }

    public static void executeAllEnergyTransfers(
            List<IEnergyStorage> extractors,
            List<IEnergyStorage> injectors,
            int overclocks,
            long currentTick,
            @Nullable java.util.Map<IEnergyStorage, Long> persistentEnergyCache,
            @Nullable Map<Object, BlockPos> handlerPositions,
            @Nullable Map<Object, Integer> handlerPriorities,
            @Nullable Map<Object, BlockEntity> handlerBlockEntities
    ) {
        if (extractors == null || extractors.isEmpty() || injectors == null || injectors.isEmpty()) {
            return;
        }

        Set<IEnergyStorage> receivedInThisTick = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<IEnergyStorage, List<IEnergyStorage>> sortedTargetsCache = new IdentityHashMap<>();

        for (IEnergyStorage extractor : extractors) {
            if (extractor == null) continue;
            if (receivedInThisTick.contains(extractor)) continue;

            List<IEnergyStorage> sortedTargets = sortedTargetsCache.computeIfAbsent(extractor, ext -> {
                BlockEntity srcBe = handlerBlockEntities != null ? handlerBlockEntities.get(ext) : null;
                BlockPos srcPos = handlerPositions != null ? handlerPositions.get(ext) : null;

                List<IEnergyStorage> list = new ArrayList<>(injectors.size());
                for (IEnergyStorage target : injectors) {
                    if (target == null || target == ext) continue;

                    BlockEntity targetBe = handlerBlockEntities != null ? handlerBlockEntities.get(target) : null;
                    if (srcBe != null && targetBe != null) {
                        if (srcBe.getClass() == targetBe.getClass() || 
                            srcBe.getBlockState().getBlock() == targetBe.getBlockState().getBlock()) {
                            continue;
                        }
                    }
                    list.add(target);
                }
                if (list.size() > 1) {
                    list.sort((t1, t2) -> {
                        int p1 = handlerPriorities != null ? handlerPriorities.getOrDefault(t1, 0) : 0;
                        int p2 = handlerPriorities != null ? handlerPriorities.getOrDefault(t2, 0) : 0;
                        if (p1 != p2) return Integer.compare(p2, p1); // 優先度降順
                        if (srcPos != null && handlerPositions != null) {
                            BlockPos pos1 = handlerPositions.get(t1);
                            BlockPos pos2 = handlerPositions.get(t2);
                            if (pos1 != null && pos2 != null) {
                                double d1 = srcPos.distSqr(pos1);
                                double d2 = srcPos.distSqr(pos2);
                                int cmp = Double.compare(d1, d2);
                                if (cmp != 0) return cmp; // 距離昇順
                                return pos1.compareTo(pos2);
                            }
                        }
                        return 0;
                    });
                }
                return list;
            });

            executeTransfer(
                    extractor,
                    sortedTargets,
                    overclocks,
                    "UUP_Energy_Extract",
                    "UUP_Energy_Insert",
                    receivedInThisTick,
                    currentTick,
                    persistentEnergyCache
            );
        }
    }

    public static long executeTransfer(
            IEnergyStorage sourceHandler,
            List<IEnergyStorage> targetHandlers,
            int overclocks,
            String sourceLabel,
            String targetLabel
    ) {
        return executeTransfer(sourceHandler, targetHandlers, overclocks, sourceLabel, targetLabel, null, 0L, null);
    }

    public static long executeTransfer(
            IEnergyStorage sourceHandler,
            List<IEnergyStorage> targetHandlers,
            int overclocks,
            String sourceLabel,
            String targetLabel,
            @Nullable Set<IEnergyStorage> receivedHandlers
    ) {
        return executeTransfer(sourceHandler, targetHandlers, overclocks, sourceLabel, targetLabel, receivedHandlers, 0L, null);
    }

    public static long executeTransfer(
            IEnergyStorage sourceHandler,
            List<IEnergyStorage> targetHandlers,
            int overclocks,
            String sourceLabel,
            String targetLabel,
            @Nullable Set<IEnergyStorage> receivedHandlers,
            long currentTick,
            @Nullable java.util.Map<IEnergyStorage, Long> persistentCache
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

            if (persistentCache != null) {
                Long expireTick = persistentCache.get(target);
                if (expireTick != null && currentTick < expireTick) {
                    continue; // 満杯TTL内なら即スキップ
                }
            }

            // 1. Check target's immediate intake demand (Simulation)
            int canAccept = target.receiveEnergy(Integer.MAX_VALUE, true);
            if (canAccept <= 0) {
                if (persistentCache != null) {
                    persistentCache.put(target, currentTick + 10L);
                }
                continue;
            }

            // 2. Limit request by remaining network quota and Integer limit
            int requestAmount = (int) Math.min(canAccept, Math.min(maxToMove - transferredTotal, (long) Integer.MAX_VALUE));
            if (requestAmount <= 0) continue;

            // 3. Extract directly from source
            int extracted = sourceHandler.extractEnergy(requestAmount, false);
            if (extracted <= 0) break; // Source empty or cannot output further

            // 4. Inject directly into target
            int received = target.receiveEnergy(extracted, false);
            transferredTotal += received;

            if (received > 0) {
                if (receivedHandlers != null) {
                    receivedHandlers.add(target);
                }
                if (persistentCache != null) {
                    persistentCache.remove(target);
                    persistentCache.remove(sourceHandler);
                }
            }

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
