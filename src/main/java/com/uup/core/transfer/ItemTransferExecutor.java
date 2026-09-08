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
        executeAllItemTransfers(extractors, injectors, overclocks, 0L, null);
    }

    public static void executeAllItemTransfers(
            List<IItemHandler> extractors,
            List<IItemHandler> injectors,
            int overclocks,
            long currentTick,
            @Nullable Map<IItemHandler, Map<ItemKey, Long>> persistentRejectionCache
    ) {
        if (extractors == null || extractors.isEmpty() || injectors == null || injectors.isEmpty()) {
            return;
        }

        Map<IItemHandler, Set<ItemKey>> sharedRejectedMap = new IdentityHashMap<>();
        Set<IItemHandler> receivedInThisTick = Collections.newSetFromMap(new IdentityHashMap<>());

        for (IItemHandler extractor : extractors) {
            if (extractor == null) continue;
            // ピンポン防止: 同一Tick内で既にアイテムを受け取ったインベントリからは搬出しない
            if (receivedInThisTick.contains(extractor)) {
                continue;
            }

            executeTransfer(
                    extractor,
                    injectors,
                    overclocks,
                    "UUP_Extract",
                    "UUP_Insert",
                    sharedRejectedMap,
                    receivedInThisTick,
                    currentTick,
                    persistentRejectionCache
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
        return executeTransfer(sourceHandler, targetHandlers, overclocks, sourceLabel, targetLabel, null, null, 0L, null);
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
        return executeTransfer(sourceHandler, targetHandlers, overclocks, sourceLabel, targetLabel, sharedRejectedMap, receivedHandlers, 0L, null);
    }

    private static final Map<IItemHandler, int[]> LAST_SLOT_CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 高速スタック挿入 (Fast-Path & 1パス走査)
     * Forge の ItemHandlerHelper.insertItemStacked が行う 2パス全走査 & リフレクション多発を回避する。
     *
     * @param target 挿入先インベントリ
     * @param stack 挿入するアイテム
     * @param lastSlotHolder 直前に挿入成功したスロット番号を保持する配列 (要素数1)
     * @return 挿入しきれなかった余り (全量入れば ItemStack.EMPTY)
     */
    public static ItemStack fastInsertItemStacked(IItemHandler target, ItemStack stack, @Nullable int[] lastSlotHolder) {
        if (target == null || stack.isEmpty()) return stack;
        int slots = target.getSlots();
        if (slots <= 0) return stack;

        // [Fast Path 1: Last-Hit Direct Insert]
        // 直前スロットに同一アイテムをそのまま追加できる場合、全スロット探索とリフレクションを完全回避 ($O(1)$)
        if (lastSlotHolder != null && lastSlotHolder[0] >= 0 && lastSlotHolder[0] < slots) {
            int lastSlot = lastSlotHolder[0];
            ItemStack inLastSlot = target.getStackInSlot(lastSlot);
            if (!inLastSlot.isEmpty() && ItemHandlerHelper.canItemStacksStack(stack, inLastSlot)) {
                int countBefore = stack.getCount();
                stack = target.insertItem(lastSlot, stack, false);
                if (stack.getCount() < countBefore) {
                    if (stack.isEmpty()) {
                        return ItemStack.EMPTY;
                    }
                }
            }
        }

        // [Fast Path 2: 1パス探索 (Single Pass)]
        // 既存同種スタックへの追加を優先しつつ、最初の空きスロット (firstEmptySlot) を同時に記録する
        int firstEmptySlot = -1;
        for (int i = 0; i < slots; i++) {
            ItemStack inSlot = target.getStackInSlot(i);
            if (inSlot.isEmpty()) {
                if (firstEmptySlot == -1) {
                    firstEmptySlot = i;
                }
            } else if (ItemHandlerHelper.canItemStacksStack(stack, inSlot)) {
                int countBefore = stack.getCount();
                stack = target.insertItem(i, stack, false);
                if (stack.getCount() < countBefore) {
                    if (lastSlotHolder != null) {
                        lastSlotHolder[0] = i;
                    }
                    if (stack.isEmpty()) {
                        return ItemStack.EMPTY;
                    }
                }
            }
        }

        // [Fast Path 3: 空きスロットへの挿入]
        // 走査開始位置を firstEmptySlot に設定することで、先頭の埋まっているスロットを完全スキップ
        if (!stack.isEmpty() && firstEmptySlot != -1) {
            for (int i = firstEmptySlot; i < slots; i++) {
                ItemStack inSlot = target.getStackInSlot(i);
                if (inSlot.isEmpty()) {
                    int countBefore = stack.getCount();
                    stack = target.insertItem(i, stack, false);
                    if (stack.getCount() < countBefore) {
                        if (lastSlotHolder != null) {
                            lastSlotHolder[0] = i;
                        }
                        if (stack.isEmpty()) {
                            return ItemStack.EMPTY;
                        }
                    }
                }
            }
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

            for (IItemHandler target : validTargets) {
                if (movedTotal >= maxToMove) break;

                // [Optimization 1] 永続的TTLキャッシュチェック (TTL 10 Tick = 0.5秒)
                if (persistentCache != null) {
                    Map<ItemKey, Long> targetCache = persistentCache.get(target);
                    if (targetCache != null) {
                        Long expireTick = targetCache.get(itemKey);
                        if (expireTick != null && currentTick < expireTick) {
                            continue; // TTL内ならシミュレーション一切なしで即スキップ！
                        }
                    }
                }

                // [Optimization 2] 同一Tick内共有キャッシュチェック
                Set<ItemKey> rejected = rejectedMap.get(target);
                if (rejected != null && rejected.contains(itemKey)) {
                    continue;
                }

                ItemStack currentInSlot = sourceHandler.getStackInSlot(slot);
                if (currentInSlot.isEmpty()) break;

                int currentLimit = (int) Math.min((long) currentInSlot.getCount(), maxToMove - movedTotal);
                if (currentLimit <= 0) break;

                // [Optimization 3: 1パス直抽出 & Last-Hit Fast Path 挿入]
                // シミュレーション二重走査 (simulate=true) を完全廃止し、直接抽出して target へ高速挿入
                ItemStack actuallyExtracted = sourceHandler.extractItem(slot, currentLimit, false);
                if (actuallyExtracted.isEmpty()) break;

                int[] lastSlotHolder = LAST_SLOT_CACHE.computeIfAbsent(target, k -> new int[]{-1});
                ItemStack remainder = fastInsertItemStacked(target, actuallyExtracted, lastSlotHolder);
                int actuallyMoved = actuallyExtracted.getCount() - remainder.getCount();

                if (actuallyMoved > 0) {
                    movedTotal += actuallyMoved;
                    if (receivedHandlers != null) {
                        receivedHandlers.add(target);
                    }
                    // 転送成功時: インベントリ空き状況が更新されたためキャッシュクリア
                    if (persistentCache != null) {
                        persistentCache.remove(target);
                        persistentCache.remove(sourceHandler);
                    }
                } else {
                    // 全く入らなかった場合: target はこの itemKey を拒絶した
                    rejectedMap.computeIfAbsent(target, k -> new HashSet<>()).add(itemKey);
                    if (persistentCache != null) {
                        persistentCache.computeIfAbsent(target, k -> new HashMap<>()).put(itemKey, currentTick + 10L);
                    }
                }

                // 挿入しきれなかった余りをソーススロットに安全にロールバック
                if (!remainder.isEmpty()) {
                    ItemStack rollbackRemainder = sourceHandler.insertItem(slot, remainder, false);
                    if (!rollbackRemainder.isEmpty()) {
                        // 万が一元のスロットに戻せなかった場合のフォールバック
                        ItemHandlerHelper.insertItemStacked(sourceHandler, rollbackRemainder, false);
                    }
                }

                // アイテムが入らなかった場合は次のターゲットを試す
                if (actuallyMoved == 0) {
                    continue;
                }

                // スロットが空になった場合は次のスロットへ
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
