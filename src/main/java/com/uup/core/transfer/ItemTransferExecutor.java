package com.uup.core.transfer;

import com.uup.config.ModConfig;
import com.uup.core.network.DirectBufferStorage;
import com.uup.logging.UUPLogger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class ItemTransferExecutor {

    public record ItemKey(Item item, @Nullable CompoundTag tag) {
        public static ItemKey of(ItemStack stack) {
            return new ItemKey(stack.getItem(), stack.hasTag() ? stack.getTag() : null);
        }
    }

    public static void executeAllItemTransfers(
            List<IItemHandler> extractors,
            List<IItemHandler> injectors,
            int overclocks
    ) {
        executeAllItemTransfers(extractors, injectors, Collections.emptyList(), Collections.emptySet(), overclocks);
    }

    public static void executeAllItemTransfers(
            List<IItemHandler> extractors,
            List<IItemHandler> injectors,
            int overclocks,
            long currentTick,
            @Nullable Map<IItemHandler, Map<ItemKey, Long>> persistentRejectionCache
    ) {
        executeAllItemTransfers(extractors, injectors, Collections.emptyList(), Collections.emptySet(), overclocks);
    }

    /**
     * Partitioned and intelligent item transfer execution.
     * When extracting from a processing machine (e.g. Mekanism Smelting Factory), items are routed ONLY
     * to storage containers (storageInjectors) sorted in Nearest-First order, completely bypassing
     * non-storage machines (machineInjectors) and eliminating 10,000+ redundant simulations per tick.
     *
     * @param extractors Item source handlers
     * @param storageInjectors Passive storage targets (Chests, Barrels, Drawers) pre-sorted Nearest-First
     * @param machineInjectors Active machine targets (Processing machines, Furnaces)
     * @param storageHandlers Set of handlers known to be passive storage
     * @param overclocks Overclock multiplier level
     */
    public static void executeAllItemTransfers(
            List<IItemHandler> extractors,
            List<IItemHandler> storageInjectors,
            List<IItemHandler> machineInjectors,
            @Nullable Set<Object> storageHandlers,
            int overclocks
    ) {
        executeAllItemTransfers(extractors, storageInjectors, machineInjectors, storageHandlers, overclocks, null);
    }

    public static void executeAllItemTransfers(
            List<IItemHandler> extractors,
            List<IItemHandler> storageInjectors,
            List<IItemHandler> machineInjectors,
            @Nullable Set<Object> storageHandlers,
            int overclocks,
            @Nullable Map<Object, net.minecraft.core.BlockPos> handlerPositions
    ) {
        executeAllItemTransfers(extractors, storageInjectors, machineInjectors, storageHandlers, overclocks, handlerPositions, 0L);
    }

    public static void executeAllItemTransfers(
            List<IItemHandler> extractors,
            List<IItemHandler> storageInjectors,
            List<IItemHandler> machineInjectors,
            @Nullable Set<Object> storageHandlers,
            int overclocks,
            @Nullable Map<Object, net.minecraft.core.BlockPos> handlerPositions,
            long currentTick
    ) {
        if (extractors == null || extractors.isEmpty()) {
            return;
        }
        boolean hasStorage = storageInjectors != null && !storageInjectors.isEmpty();
        boolean hasMachine = machineInjectors != null && !machineInjectors.isEmpty();
        if (!hasStorage && !hasMachine) {
            return;
        }

        List<IItemHandler> allInjectors;
        if (!hasMachine) {
            allInjectors = storageInjectors;
        } else if (!hasStorage) {
            allInjectors = machineInjectors;
        } else {
            allInjectors = new ArrayList<>(storageInjectors.size() + machineInjectors.size());
            allInjectors.addAll(storageInjectors);
            allInjectors.addAll(machineInjectors);
        }

        Map<IItemHandler, Set<ItemKey>> tickRejectedMap = new IdentityHashMap<>();
        Set<IItemHandler> receivedInThisTick = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ItemKey> tickAllMachinesFullSet = new HashSet<>();

        for (IItemHandler extractor : extractors) {
            if (extractor == null) continue;
            // Ping-pong prevention: do not extract from an inventory that already received items this tick
            if (receivedInThisTick.contains(extractor)) {
                continue;
            }

            // Respect machine side configs: allow transferring to all configured injectors
            // Machine side configurations and Forge capabilities naturally govern input acceptance.
            executeTransfer(
                    extractor,
                    allInjectors,
                    overclocks,
                    "UUP_Extract",
                    "UUP_Insert",
                    tickRejectedMap,
                    receivedInThisTick,
                    currentTick,
                    null,
                    handlerPositions,
                    storageHandlers,
                    tickAllMachinesFullSet
            );
        }
    }

    public static long executeTransfer(
            IItemHandler sourceHandler,
            List<IItemHandler> targetHandlers,
            int overclocks,
            String sourceLabel,
            String targetLabel
    ) {
        return executeTransfer(sourceHandler, targetHandlers, overclocks, sourceLabel, targetLabel, null, null);
    }

    public static long executeTransfer(
            IItemHandler sourceHandler,
            List<IItemHandler> targetHandlers,
            int overclocks,
            String sourceLabel,
            String targetLabel,
            @Nullable Map<IItemHandler, Set<ItemKey>> sharedRejectedMap,
            @Nullable Set<IItemHandler> receivedHandlers
    ) {
        return executeTransfer(sourceHandler, targetHandlers, overclocks, sourceLabel, targetLabel, sharedRejectedMap, receivedHandlers, 0L, null, null);
    }

    public static class TargetState {
        final Map<ItemKey, Integer> lastSlotByItem = new HashMap<>();
        final Map<ItemKey, Long> fullItemTicks = new HashMap<>();
        int firstEmptySlot = 0;
        long lastEmptySlotResetTick = 0;

        public boolean isFull(ItemKey itemKey, long tick) {
            Long fullTick = fullItemTicks.get(itemKey);
            if (fullTick == null) return false;
            if (tick - fullTick > 1200) { // 1200 ticks (60s) fallback TTL for auto-recovery
                fullItemTicks.remove(itemKey);
                return false;
            }
            return true;
        }

        public void markFull(ItemKey itemKey, long tick) {
            fullItemTicks.put(itemKey, tick);
        }

        public void removeFull(ItemKey itemKey) {
            fullItemTicks.remove(itemKey);
        }

        public void clearAll() {
            lastSlotByItem.clear();
            fullItemTicks.clear();
            firstEmptySlot = 0;
        }

        public void updateEmptySlotTracking(long tick) {
            if (tick - lastEmptySlotResetTick > 1200) {
                firstEmptySlot = 0;
                lastEmptySlotResetTick = tick;
            }
        }
    }

    private static final Map<IItemHandler, TargetState> TARGET_STATE_CACHE = 
            Collections.synchronizedMap(new WeakHashMap<>());

    // Active nearest target cache: routes items directly to the active filling container in O(1) (for Storage)
    private static final Map<ItemKey, IItemHandler> ACTIVE_TARGET_BY_ITEM = 
            Collections.synchronizedMap(new WeakHashMap<>());

    // Active storage search cursor per item: tracks the starting index of non-full storage containers in O(1)
    private static final Map<ItemKey, Integer> STORAGE_SEARCH_CURSORS = 
            new java.util.concurrent.ConcurrentHashMap<>();

    // Round-robin cursor per source handler to distribute items evenly across processing machines
    private static final Map<IItemHandler, Integer> ROUND_ROBIN_CURSORS = 
            Collections.synchronizedMap(new WeakHashMap<>());

    // Global round-robin cursor across the entire pipe network for perfect machine balancing
    private static final java.util.concurrent.atomic.AtomicInteger GLOBAL_MACHINE_CURSOR = 
            new java.util.concurrent.atomic.AtomicInteger(0);

    public static void clearTargetStateCache() {
        TARGET_STATE_CACHE.clear();
        ACTIVE_TARGET_BY_ITEM.clear();
        STORAGE_SEARCH_CURSORS.clear();
        ROUND_ROBIN_CURSORS.clear();
    }

    // Cache max stack size per item to prevent BiggerStacks mod's heavy Thread.getStackTrace() overhead
    private static final Map<ItemKey, Integer> ITEM_MAX_STACK_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static int getMaxStackSizeFast(ItemStack stack, ItemKey key) {
        return ITEM_MAX_STACK_CACHE.computeIfAbsent(key, k -> stack.getMaxStackSize());
    }

    /**
     * 超高速スタック判定 (Forge の CapabilityProvider.areCapsCompatible / gatherCapabilities を完全バイパス)
     * Forge は ItemStack.isSameItemSameTags の末尾に areCapsCompatible を注入しており、
     * これが毎スロット ForgeEventFactory.gatherCapabilities を発火させて 11秒ものCPU時間を消費していた。
     * 本メソッドは Item, DamageValue, NBTTag の完全一致のみを純粋に判定し、Forge イベント発火を完全ゼロにする。
     */
    public static boolean canItemsStackFast(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.getItem() != b.getItem()) return false;
        if (a.getDamageValue() != b.getDamageValue()) return false;
        
        CompoundTag tagA = a.getTag();
        CompoundTag tagB = b.getTag();
        if (tagA == null) {
            return tagB == null;
        }
        return tagA.equals(tagB);
    }

    /**
     * 高速スタック挿入 (Fast-Path & 1パス走査)
     * Forge の ItemHandlerHelper.insertItemStacked が行う 2パス全走査 & リフレクション多発を回避する。
     *
     * @param target 挿入先インベントリ
     * @param stack 挿入するアイテム
     * @param state ターゲット状態キャッシュ (lastSlotByItem, firstEmptySlot)
     * @param itemKey アイテム識別キー
     * @param simulate シミュレーション実行フラグ (true の場合は状態更新を行わない)
     * @return 挿入しきれなかった余り (全量入れば ItemStack.EMPTY)
     */
    public static ItemStack fastInsertItemStacked(
            IItemHandler target,
            ItemStack stack,
            @Nullable TargetState state,
            ItemKey itemKey,
            boolean simulate
    ) {
        return fastInsertItemStacked(target, stack, state, itemKey, simulate, 0L);
    }

    public static ItemStack fastInsertItemStacked(
            IItemHandler target,
            ItemStack stack,
            @Nullable TargetState state,
            ItemKey itemKey,
            boolean simulate,
            long currentTick
    ) {
        if (target == null || stack.isEmpty()) return stack;
        long effectiveTick = currentTick > 0 ? currentTick : (System.currentTimeMillis() / 50);
        if (state != null) {
            state.updateEmptySlotTracking(effectiveTick);
            if (state.isFull(itemKey, effectiveTick)) {
                return stack; // Immediate O(1) skip for known full targets within 20 ticks TTL
            }
        }
        int slots = target.getSlots();
        if (slots <= 0) return stack;

        int initialCount = stack.getCount();
        int maxStack = getMaxStackSizeFast(stack, itemKey);

        // [Fast Path 1: アイテム別 Last-Hit Direct Insert ($O(1)$)]
        if (state != null) {
            Integer lastSlotObj = state.lastSlotByItem.get(itemKey);
            if (lastSlotObj != null) {
                int lastSlot = lastSlotObj;
                if (lastSlot >= 0 && lastSlot < slots) {
                    ItemStack inLastSlot = target.getStackInSlot(lastSlot);
                    if (!inLastSlot.isEmpty() && canItemsStackFast(stack, inLastSlot)) {
                        if (inLastSlot.getCount() < maxStack && inLastSlot.getCount() < target.getSlotLimit(lastSlot)) {
                            int countBefore = stack.getCount();
                            stack = target.insertItem(lastSlot, stack, simulate);
                            if (stack.getCount() < countBefore) {
                                state.removeFull(itemKey);
                                if (stack.isEmpty()) {
                                    return ItemStack.EMPTY;
                                }
                            } else if (!simulate) {
                                state.lastSlotByItem.remove(itemKey);
                            }
                        } else if (!simulate) {
                            state.lastSlotByItem.remove(itemKey);
                        }
                    } else if (!simulate) {
                        state.lastSlotByItem.remove(itemKey);
                    }
                } else if (!simulate) {
                    state.lastSlotByItem.remove(itemKey);
                }
            }
        }

        // [Fast Path 2: 既存同種スタックへの追加探索 & 空きスロット検出]
        int foundEmptySlot = -1;

        for (int i = 0; i < slots; i++) {
            ItemStack inSlot = target.getStackInSlot(i);
            if (inSlot.isEmpty()) {
                if (foundEmptySlot == -1) {
                    foundEmptySlot = i;
                }
            } else if (canItemsStackFast(stack, inSlot)) {
                // Skip full slots immediately without invoking heavy insertItem simulation or BiggerStacks getStackTrace()
                if (inSlot.getCount() >= maxStack || inSlot.getCount() >= target.getSlotLimit(i)) {
                    continue;
                }
                int countBefore = stack.getCount();
                stack = target.insertItem(i, stack, simulate);
                if (stack.getCount() < countBefore) {
                    if (state != null) {
                        if (!simulate) state.lastSlotByItem.put(itemKey, i);
                        state.removeFull(itemKey);
                    }
                    if (stack.isEmpty()) {
                        return ItemStack.EMPTY;
                    }
                }
            }
        }

        // 空きスロットの追跡ポインタを更新
        if (foundEmptySlot != -1 && state != null && !simulate) {
            state.firstEmptySlot = foundEmptySlot;
        }

        // [Fast Path 3: 空きスロットへの挿入 (空きスロットが検出された場合のみ走査)]
        if (!stack.isEmpty() && foundEmptySlot != -1) {
            for (int i = foundEmptySlot; i < slots; i++) {
                ItemStack inSlot = target.getStackInSlot(i);
                if (inSlot.isEmpty()) {
                    int countBefore = stack.getCount();
                    stack = target.insertItem(i, stack, simulate);
                    if (stack.getCount() < countBefore) {
                        if (state != null) {
                            if (!simulate) {
                                state.lastSlotByItem.put(itemKey, i);
                                state.firstEmptySlot = i + 1;
                            }
                            state.removeFull(itemKey);
                        }
                        if (stack.isEmpty()) {
                            return ItemStack.EMPTY;
                        }
                    }
                }
            }
        }

        // 挿入できず、これ以上入らない場合はシミュレーション時でも fullItems に即時記録 (O(1)スキップを有効化)
        if (stack.getCount() == initialCount && state != null) {
            state.markFull(itemKey, effectiveTick);
        }

        return stack;
    }

    public static long executeTransfer(
            IItemHandler sourceHandler,
            List<IItemHandler> targetHandlers,
            int overclocks,
            String sourceLabel,
            String targetLabel,
            @Nullable Map<IItemHandler, Set<ItemKey>> sharedRejectedMap,
            @Nullable Set<IItemHandler> receivedHandlers,
            long currentTick,
            @Nullable Map<IItemHandler, Map<ItemKey, Long>> persistentCache
    ) {
        return executeTransfer(sourceHandler, targetHandlers, overclocks, sourceLabel, targetLabel, sharedRejectedMap, receivedHandlers, currentTick, persistentCache, null);
    }

    public static long executeTransfer(
            IItemHandler sourceHandler,
            List<IItemHandler> targetHandlers,
            int overclocks,
            String sourceLabel,
            String targetLabel,
            @Nullable Map<IItemHandler, Set<ItemKey>> sharedRejectedMap,
            @Nullable Set<IItemHandler> receivedHandlers,
            long currentTick,
            @Nullable Map<IItemHandler, Map<ItemKey, Long>> persistentCache,
            @Nullable Map<Object, net.minecraft.core.BlockPos> handlerPositions
    ) {
        return executeTransfer(sourceHandler, targetHandlers, overclocks, sourceLabel, targetLabel, sharedRejectedMap, receivedHandlers, currentTick, persistentCache, handlerPositions, null);
    }

    public static long executeTransfer(
            IItemHandler sourceHandler,
            List<IItemHandler> targetHandlers,
            int overclocks,
            String sourceLabel,
            String targetLabel,
            @Nullable Map<IItemHandler, Set<ItemKey>> sharedRejectedMap,
            @Nullable Set<IItemHandler> receivedHandlers,
            long currentTick,
            @Nullable Map<IItemHandler, Map<ItemKey, Long>> persistentCache,
            @Nullable Map<Object, net.minecraft.core.BlockPos> handlerPositions,
            @Nullable Set<Object> storageHandlers
    ) {
        return executeTransfer(sourceHandler, targetHandlers, overclocks, sourceLabel, targetLabel, sharedRejectedMap, receivedHandlers, currentTick, persistentCache, handlerPositions, storageHandlers, null);
    }

    public static long executeTransfer(
            IItemHandler sourceHandler,
            List<IItemHandler> targetHandlers,
            int overclocks,
            String sourceLabel,
            String targetLabel,
            @Nullable Map<IItemHandler, Set<ItemKey>> sharedRejectedMap,
            @Nullable Set<IItemHandler> receivedHandlers,
            long currentTick,
            @Nullable Map<IItemHandler, Map<ItemKey, Long>> persistentCache,
            @Nullable Map<Object, net.minecraft.core.BlockPos> handlerPositions,
            @Nullable Set<Object> storageHandlers,
            @Nullable Set<ItemKey> tickAllMachinesFullSet
    ) {
        return executeTransfer(sourceHandler, targetHandlers, overclocks, sourceLabel, targetLabel, sharedRejectedMap, receivedHandlers, currentTick, persistentCache, handlerPositions, storageHandlers, tickAllMachinesFullSet, null, null);
    }

    public static long executeTransfer(
            IItemHandler sourceHandler,
            List<IItemHandler> targetHandlers,
            int overclocks,
            String sourceLabel,
            String targetLabel,
            @Nullable Map<IItemHandler, Set<ItemKey>> sharedRejectedMap,
            @Nullable Set<IItemHandler> receivedHandlers,
            long currentTick,
            @Nullable Map<IItemHandler, Map<ItemKey, Long>> persistentCache,
            @Nullable Map<Object, net.minecraft.core.BlockPos> handlerPositions,
            @Nullable Set<Object> storageHandlers,
            @Nullable Set<ItemKey> tickAllMachinesFullSet,
            @Nullable net.minecraft.world.level.block.entity.BlockEntity sourceBE,
            @Nullable net.minecraft.core.Direction sourceSide
    ) {
        if (sourceHandler == null || targetHandlers == null || targetHandlers.isEmpty()) {
            return 0;
        }

        long effectiveTick = currentTick > 0 ? currentTick : (System.currentTimeMillis() / 50);

        int baseRate = ModConfig.COMMON != null && ModConfig.COMMON.baseItemTransferRate != null 
                ? ModConfig.COMMON.baseItemTransferRate.get() : Integer.MAX_VALUE;
        double multiplier = ModConfig.COMMON != null && ModConfig.COMMON.overclockItemMultiplier != null 
                ? ModConfig.COMMON.overclockItemMultiplier.get() : 4.0;
        
        long maxToMove = (long) (baseRate * Math.pow(multiplier, Math.min(overclocks, 16)));
        if (maxToMove <= 0) maxToMove = Integer.MAX_VALUE;

        long movedTotal = 0;
        int slots = sourceHandler.getSlots();

        Map<IItemHandler, Set<ItemKey>> rejectedMap = sharedRejectedMap != null 
                ? sharedRejectedMap : new IdentityHashMap<>();

        // Partition targetHandlers into storage vs machine targets
        List<IItemHandler> storageTargets = null;
        List<IItemHandler> machineTargets = null;

        if (storageHandlers != null) {
            for (IItemHandler target : targetHandlers) {
                if (target == null || target == sourceHandler) continue;
                if (storageHandlers.contains(target)) {
                    if (storageTargets == null) storageTargets = new ArrayList<>();
                    storageTargets.add(target);
                } else {
                    if (machineTargets == null) machineTargets = new ArrayList<>();
                    machineTargets.add(target);
                }
            }
        } else {
            storageTargets = targetHandlers;
        }

        int numMachines = machineTargets != null ? machineTargets.size() : 0;
        int opCount = 0;
        int maxOperations = 512; // Prevents freezing while allowing extreme throughput: up to 32,768 items/tick

        for (int slot = 0; slot < slots && movedTotal < maxToMove && opCount < maxOperations; slot++) {
            // 機械からの搬出時、加工後（出力）スロットのみから排出（加工前の丸石などの誤吸い出しを物理的に完全遮断！）
            if (sourceBE != null && !com.uup.core.network.MachineSideDetector.isOutputSlot(sourceBE, sourceSide, slot, slots)) {
                continue;
            }

            while (movedTotal < maxToMove && opCount++ < maxOperations) {
                ItemStack inSlot = sourceHandler.getStackInSlot(slot);
                if (inSlot.isEmpty()) break;

                ItemKey itemKey = ItemKey.of(inSlot);
                boolean transferred = false;

                // 1. Storage ターゲットへの搬入 (Sticky Nearest-First with Active Cursor)
                if (storageTargets != null && !storageTargets.isEmpty()) {
                    IItemHandler activeTarget = ACTIVE_TARGET_BY_ITEM.get(itemKey);
                    if (activeTarget != null && activeTarget != sourceHandler && storageTargets.contains(activeTarget)) {
                        TargetState state = TARGET_STATE_CACHE.get(activeTarget);
                        if (state != null && state.isFull(itemKey, effectiveTick)) {
                            ACTIVE_TARGET_BY_ITEM.remove(itemKey);
                        } else {
                            Set<ItemKey> rejected = rejectedMap.get(activeTarget);
                            if (rejected == null || !rejected.contains(itemKey)) {
                                int moved = tryTransferSlot(sourceHandler, activeTarget, slot, itemKey, maxToMove - movedTotal, rejectedMap, receivedHandlers, handlerPositions, sourceLabel, targetLabel, effectiveTick);
                                if (moved > 0) {
                                    movedTotal += moved;
                                    transferred = true;
                                } else {
                                    ACTIVE_TARGET_BY_ITEM.remove(itemKey);
                                }
                            }
                        }
                    }

                    if (!transferred) {
                        int numStorage = storageTargets.size();
                        int startIdx = STORAGE_SEARCH_CURSORS.getOrDefault(itemKey, 0);
                        if (startIdx >= numStorage) {
                            startIdx = 0;
                            STORAGE_SEARCH_CURSORS.put(itemKey, 0);
                        }

                        // 【巡回プローブ】カーソルが手前（0）以外にある場合、毎tick 1個だけ手前チェストをテスト！
                        // 1,500万回の全走査スパイクを完全根絶し、1 tick 負荷 0.001ms で手前空きを即時検知＆カーソル復帰
                        if (startIdx > 0) {
                            int probeIdx = (int) (Math.abs(effectiveTick) % startIdx);
                            IItemHandler probeTarget = storageTargets.get(probeIdx);
                            if (probeTarget != null && probeTarget != sourceHandler) {
                                TargetState probeState = TARGET_STATE_CACHE.get(probeTarget);
                                if (probeState != null) {
                                    int probeMoved = tryTransferSlot(sourceHandler, probeTarget, slot, itemKey, maxToMove - movedTotal, rejectedMap, receivedHandlers, handlerPositions, sourceLabel, targetLabel, effectiveTick);
                                    if (probeMoved > 0) {
                                        movedTotal += probeMoved;
                                        ACTIVE_TARGET_BY_ITEM.put(itemKey, probeTarget);
                                        STORAGE_SEARCH_CURSORS.put(itemKey, probeIdx);
                                        transferred = true;
                                    }
                                }
                            }
                        }

                        if (!transferred) {
                            for (int i = startIdx; i < numStorage; i++) {
                                IItemHandler target = storageTargets.get(i);
                                if (target == null || target == sourceHandler || target == activeTarget) continue;

                                Set<ItemKey> rejected = rejectedMap.get(target);
                                if (rejected != null && rejected.contains(itemKey)) {
                                    continue;
                                }

                                // 満杯キャッシュの事前チェック ($O(1)$)
                                TargetState state = TARGET_STATE_CACHE.get(target);
                                if (state != null && state.isFull(itemKey, effectiveTick)) {
                                    rejectedMap.computeIfAbsent(target, k -> new HashSet<>()).add(itemKey);
                                    if (i == startIdx) {
                                        startIdx++;
                                        STORAGE_SEARCH_CURSORS.put(itemKey, startIdx);
                                    }
                                    continue;
                                }

                                int moved = tryTransferSlot(sourceHandler, target, slot, itemKey, maxToMove - movedTotal, rejectedMap, receivedHandlers, handlerPositions, sourceLabel, targetLabel, effectiveTick);
                                if (moved > 0) {
                                    movedTotal += moved;
                                    ACTIVE_TARGET_BY_ITEM.put(itemKey, target);
                                    STORAGE_SEARCH_CURSORS.put(itemKey, i); // 次回はここから直接探索！
                                    transferred = true;
                                    break;
                                } else {
                                    if (i == startIdx) {
                                        startIdx++;
                                        STORAGE_SEARCH_CURSORS.put(itemKey, startIdx);
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Storage に入らなかった場合、Machine ターゲットへの均等分配 (Network Global Round-Robin)
                if (!transferred && numMachines > 0) {
                    // 全マシンがこのアイテムについて満杯と判明している場合は O(1) で即座にスキップ！
                    if (tickAllMachinesFullSet != null && tickAllMachinesFullSet.contains(itemKey)) {
                        break;
                    }

                    int startCursor = GLOBAL_MACHINE_CURSOR.get() % numMachines;
                    if (startCursor < 0) startCursor = 0;

                    for (int offset = 0; offset < numMachines && movedTotal < maxToMove; offset++) {
                        int targetIdx = (startCursor + offset) % numMachines;
                        IItemHandler target = machineTargets.get(targetIdx);
                        if (target == null || target == sourceHandler) continue;

                        Set<ItemKey> rejected = rejectedMap.get(target);
                        if (rejected != null && rejected.contains(itemKey)) {
                            continue;
                        }

                        // 満杯キャッシュの事前チェック ($O(1)$)
                        TargetState state = TARGET_STATE_CACHE.get(target);
                        if (state != null && state.isFull(itemKey, effectiveTick)) {
                            rejectedMap.computeIfAbsent(target, k -> new HashSet<>()).add(itemKey);
                            continue;
                        }

                        int moved = tryTransferSlot(sourceHandler, target, slot, itemKey, maxToMove - movedTotal, rejectedMap, receivedHandlers, handlerPositions, sourceLabel, targetLabel, effectiveTick);
                        if (moved > 0) {
                            movedTotal += moved;
                            GLOBAL_MACHINE_CURSOR.set((targetIdx + 1) % numMachines);
                            transferred = true;
                            break; // 次のマシンへ分散投入
                        }
                    }

                    // 1周探索してもどのマシンにも入らなかった場合、このTickでは全マシン満杯として即座にマーク
                    if (!transferred && tickAllMachinesFullSet != null) {
                        tickAllMachinesFullSet.add(itemKey);
                    }
                }

                if (!transferred) {
                    break;
                }
            }
        }

        if (movedTotal > 0) {
            UUPLogger.logTransfer("ITEM", movedTotal, sourceLabel, targetLabel);
        }
        return movedTotal;
    }

    private static int tryTransferSlot(
            IItemHandler sourceHandler,
            IItemHandler target,
            int slot,
            ItemKey itemKey,
            long remainingMoveLimit,
            Map<IItemHandler, Set<ItemKey>> rejectedMap,
            @Nullable Set<IItemHandler> receivedHandlers,
            @Nullable Map<Object, net.minecraft.core.BlockPos> handlerPositions,
            String sourceLabel,
            String targetLabel,
            long currentTick
    ) {
        ItemStack currentInSlot = sourceHandler.getStackInSlot(slot);
        if (currentInSlot.isEmpty()) return 0;

        int currentLimit = (int) Math.min((long) currentInSlot.getCount(), remainingMoveLimit);
        if (currentLimit <= 0) return 0;

        TargetState targetState = TARGET_STATE_CACHE.computeIfAbsent(target, k -> new TargetState());
        if (targetState.isFull(itemKey, currentTick)) {
            rejectedMap.computeIfAbsent(target, k -> new HashSet<>()).add(itemKey);
            return 0;
        }

        ItemStack probeStack = currentInSlot.copy();
        probeStack.setCount(currentLimit);

        ItemStack simRemainder = fastInsertItemStacked(target, probeStack, targetState, itemKey, true, currentTick);
        int accepted = currentLimit - simRemainder.getCount();

        if (accepted <= 0) {
            rejectedMap.computeIfAbsent(target, k -> new HashSet<>()).add(itemKey);
            return 0;
        }

        ItemStack actuallyExtracted = sourceHandler.extractItem(slot, accepted, false);
        if (actuallyExtracted.isEmpty()) return 0;

        TargetState srcState = TARGET_STATE_CACHE.get(sourceHandler);
        if (srcState != null) {
            srcState.clearAll();
        }

        ItemStack realRemainder = fastInsertItemStacked(target, actuallyExtracted, targetState, itemKey, false, currentTick);
        int actuallyMoved = actuallyExtracted.getCount() - realRemainder.getCount();

        if (actuallyMoved > 0) {
            if (receivedHandlers != null) {
                receivedHandlers.add(target);
            }
            targetState.removeFull(itemKey);

            net.minecraft.core.BlockPos srcPos = handlerPositions != null ? handlerPositions.get(sourceHandler) : null;
            net.minecraft.core.BlockPos dstPos = handlerPositions != null ? handlerPositions.get(target) : null;
            String srcStr = srcPos != null ? srcPos.toShortString() : sourceLabel;
            String dstStr = dstPos != null ? dstPos.toShortString() : targetLabel;
            String itemName = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(actuallyExtracted.getItem()).toString();
            UUPLogger.logRoute(String.format("[TargetRoute] %dx %s from %s -> %s", actuallyMoved, itemName, srcStr, dstStr));
        }

        if (!realRemainder.isEmpty()) {
            sourceHandler.insertItem(slot, realRemainder, false);
        }

        return actuallyMoved;
    }

    public static void dispatchInternalBuffer(
            DirectBufferStorage storage,
            List<IItemHandler> targetHandlers,
            int overclocks
    ) {
        if (storage == null || targetHandlers == null || targetHandlers.isEmpty()) return;
        executeTransfer(storage.getItemBuffer(), targetHandlers, overclocks, "UUP_Controller_Buffer", "Network_Targets");
    }

    public static void ingestToInternalBuffer(
            DirectBufferStorage storage,
            List<IItemHandler> sourceHandlers,
            int overclocks
    ) {
        if (storage == null || sourceHandlers == null || sourceHandlers.isEmpty()) return;
        List<IItemHandler> target = List.of(storage.getItemBuffer());
        for (IItemHandler source : sourceHandlers) {
            executeTransfer(source, target, overclocks, "Network_Source", "UUP_Controller_Buffer");
        }
    }
}
