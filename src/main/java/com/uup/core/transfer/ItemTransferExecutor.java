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
                    0L,
                    null,
                    handlerPositions
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

    private static class TargetState {
        final Map<ItemKey, Integer> lastSlotByItem = new HashMap<>();
        final Set<ItemKey> fullItems = new HashSet<>();
        int firstEmptySlot = 0;
    }

    private static final Map<IItemHandler, TargetState> TARGET_STATE_CACHE = 
            Collections.synchronizedMap(new WeakHashMap<>());

    // Active nearest target cache: routes items directly to the active filling container in O(1)
    private static final Map<ItemKey, IItemHandler> ACTIVE_TARGET_BY_ITEM = 
            Collections.synchronizedMap(new WeakHashMap<>());

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
        if (target == null || stack.isEmpty()) return stack;
        if (state != null && state.fullItems.contains(itemKey)) {
            return stack; // Immediate O(1) skip for known full targets
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
                                if (state != null) state.fullItems.remove(itemKey);
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
                        state.fullItems.remove(itemKey);
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
                            state.fullItems.remove(itemKey);
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
            state.fullItems.add(itemKey);
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
        if (sourceHandler == null || targetHandlers == null || targetHandlers.isEmpty()) {
            return 0;
        }

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

        List<IItemHandler> validTargets = new ArrayList<>(targetHandlers.size());
        for (IItemHandler target : targetHandlers) {
            if (target != null && target != sourceHandler) {
                validTargets.add(target);
            }
        }
        if (validTargets.isEmpty()) return 0;

        for (int slot = 0; slot < slots && movedTotal < maxToMove; slot++) {
            ItemStack inSlot = sourceHandler.getStackInSlot(slot);
            if (inSlot.isEmpty()) continue;

            ItemKey itemKey = ItemKey.of(inSlot);

            // [Active Nearest-First Direct Routing]
            // 1. Always try the nearest target (validTargets.get(0)) first to preserve closest-fill priority.
            // 2. If the nearest is full, try activeTarget second, completely skipping dozens of full chests in O(1).
            // 3. Fall back to sequential pre-sorted order for new targets.
            IItemHandler nearestTarget = validTargets.get(0);
            IItemHandler activeTarget = ACTIVE_TARGET_BY_ITEM.get(itemKey);
            if (activeTarget != null && (activeTarget == sourceHandler || !validTargets.contains(activeTarget))) {
                ACTIVE_TARGET_BY_ITEM.remove(itemKey);
                activeTarget = null;
            }

            List<IItemHandler> targetsToTry;
            if (activeTarget != null && activeTarget != nearestTarget) {
                targetsToTry = new ArrayList<>(validTargets.size());
                targetsToTry.add(nearestTarget);
                targetsToTry.add(activeTarget);
                for (IItemHandler t : validTargets) {
                    if (t != nearestTarget && t != activeTarget) {
                        targetsToTry.add(t);
                    }
                }
            } else {
                targetsToTry = validTargets;
            }

            for (IItemHandler target : targetsToTry) {
                if (movedTotal >= maxToMove) break;

                // Intra-tick rejection check: skip targets already proven full for this item in THIS tick
                Set<ItemKey> rejected = rejectedMap.get(target);
                if (rejected != null && rejected.contains(itemKey)) {
                    continue;
                }

                ItemStack currentInSlot = sourceHandler.getStackInSlot(slot);
                if (currentInSlot.isEmpty()) break;

                int currentLimit = (int) Math.min((long) currentInSlot.getCount(), maxToMove - movedTotal);
                if (currentLimit <= 0) break;

                // [Simulation verification: 0 wasted extraction and 0 unnecessary rollbacks]
                ItemStack probeStack = currentInSlot.copy();
                probeStack.setCount(currentLimit);

                TargetState targetState = TARGET_STATE_CACHE.computeIfAbsent(target, k -> new TargetState());

                // 最寄りターゲットは常に空き復帰を即座に検知できるよう、fullItems を一時クリアしてプローブ
                if (target == nearestTarget) {
                    targetState.fullItems.remove(itemKey);
                }

                ItemStack simRemainder = fastInsertItemStacked(target, probeStack, targetState, itemKey, true);
                int accepted = currentLimit - simRemainder.getCount();

                if (accepted <= 0) {
                    // Mark as rejected only within the current tick to prevent target hopping across ticks
                    rejectedMap.computeIfAbsent(target, k -> new HashSet<>()).add(itemKey);
                    if (target == activeTarget) {
                        ACTIVE_TARGET_BY_ITEM.remove(itemKey);
                    }
                    continue;
                }

                // Extract only what is guaranteed to be accepted
                ItemStack actuallyExtracted = sourceHandler.extractItem(slot, accepted, false);
                if (actuallyExtracted.isEmpty()) break;

                // 抽出元に空きができたため、抽出元の fullItems を即座にクリア
                TargetState srcState = TARGET_STATE_CACHE.get(sourceHandler);
                if (srcState != null) {
                    srcState.fullItems.clear();
                }

                // Insert into target
                ItemStack realRemainder = fastInsertItemStacked(target, actuallyExtracted, targetState, itemKey, false);
                int actuallyMoved = actuallyExtracted.getCount() - realRemainder.getCount();

                if (actuallyMoved > 0) {
                    movedTotal += actuallyMoved;
                    ACTIVE_TARGET_BY_ITEM.put(itemKey, target); // Directly route subsequent items here in O(1)
                    if (receivedHandlers != null) {
                        receivedHandlers.add(target);
                    }
                    targetState.fullItems.remove(itemKey);

                    // ユーザー要望：どこにターゲッティングして移動したかを詳細ログ出力
                    net.minecraft.core.BlockPos srcPos = handlerPositions != null ? handlerPositions.get(sourceHandler) : null;
                    net.minecraft.core.BlockPos dstPos = handlerPositions != null ? handlerPositions.get(target) : null;
                    String srcStr = srcPos != null ? srcPos.toShortString() : sourceLabel;
                    String dstStr = dstPos != null ? dstPos.toShortString() : targetLabel;
                    String itemName = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(actuallyExtracted.getItem()).toString();
                    UUPLogger.logRoute(String.format("[TargetRoute] %dx %s from %s -> %s", actuallyMoved, itemName, srcStr, dstStr));
                }

                // Minimal rollback in rare edge cases
                if (!realRemainder.isEmpty()) {
                    sourceHandler.insertItem(slot, realRemainder, false);
                }

                if (actuallyMoved == 0) {
                    continue;
                }

                if (sourceHandler.getStackInSlot(slot).isEmpty()) {
                    break;
                }
            }
        }

        if (movedTotal > 0) {
            UUPLogger.logTransfer("ITEM", movedTotal, sourceLabel, targetLabel);
        }
        return movedTotal;
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
