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

    private static class TargetState {
        int lastSlot = -1;
        int firstEmptySlot = 0;
    }

    private static final Map<IItemHandler, TargetState> TARGET_STATE_CACHE = 
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 高速スタック判定 (Forge の areCapsCompatible によるイベント発火を完全バイパス)
     * スタック可能アイテムにおいては、Vanilla の Item/Damage/NBT 一致判定で 100% 安全かつ超高速に判定可能。
     */
    public static boolean canItemsStackFast(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.getItem() != b.getItem()) return false;
        if (a.getDamageValue() != b.getDamageValue()) return false;
        return ItemStack.isSameItemSameTags(a, b);
    }

    /**
     * 高速スタック挿入 (Fast-Path & 1パス走査)
     * Forge の ItemHandlerHelper.insertItemStacked が行う 2パス全走査 & リフレクション多発を回避する。
     *
     * @param target 挿入先インベントリ
     * @param stack 挿入するアイテム
     * @param state ターゲット状態キャッシュ (lastSlot, firstEmptySlot)
     * @return 挿入しきれなかった余り (全量入れば ItemStack.EMPTY)
     */
    public static ItemStack fastInsertItemStacked(IItemHandler target, ItemStack stack, @Nullable TargetState state) {
        if (target == null || stack.isEmpty()) return stack;
        int slots = target.getSlots();
        if (slots <= 0) return stack;

        // [Fast Path 1: Last-Hit Direct Insert]
        // 直前スロットに同一アイテムをそのまま追加できる場合、全スロット探索とリフレクションを完全回避 ($O(1)$)
        if (state != null && state.lastSlot >= 0 && state.lastSlot < slots) {
            int lastSlot = state.lastSlot;
            ItemStack inLastSlot = target.getStackInSlot(lastSlot);
            if (!inLastSlot.isEmpty() && canItemsStackFast(stack, inLastSlot)) {
                int countBefore = stack.getCount();
                stack = target.insertItem(lastSlot, stack, false);
                if (stack.getCount() < countBefore) {
                    if (stack.isEmpty()) {
                        return ItemStack.EMPTY;
                    }
                } else {
                    // スロットが満杯になったためリセット
                    state.lastSlot = -1;
                }
            } else {
                state.lastSlot = -1;
            }
        }

        // [Fast Path 2: 既存同種スタックへの追加探索]
        int firstEmpty = (state != null && state.firstEmptySlot >= 0 && state.firstEmptySlot < slots) 
                ? state.firstEmptySlot : 0;
        int foundEmptySlot = -1;

        for (int i = 0; i < slots; i++) {
            ItemStack inSlot = target.getStackInSlot(i);
            if (inSlot.isEmpty()) {
                if (foundEmptySlot == -1) {
                    foundEmptySlot = i;
                }
            } else if (canItemsStackFast(stack, inSlot)) {
                int countBefore = stack.getCount();
                stack = target.insertItem(i, stack, false);
                if (stack.getCount() < countBefore) {
                    if (state != null) {
                        state.lastSlot = i;
                    }
                    if (stack.isEmpty()) {
                        return ItemStack.EMPTY;
                    }
                }
            }
        }

        // 空きスロットの追跡ポインタを更新
        if (foundEmptySlot != -1 && state != null) {
            state.firstEmptySlot = foundEmptySlot;
        }

        // [Fast Path 3: 空きスロットへの挿入]
        // 走査開始位置を記録された空きスロットから開始することで、先頭の埋まっているスロットを完全スキップ
        int startSearchEmpty = (foundEmptySlot != -1) ? foundEmptySlot : firstEmpty;
        if (!stack.isEmpty() && startSearchEmpty < slots) {
            for (int i = startSearchEmpty; i < slots; i++) {
                ItemStack inSlot = target.getStackInSlot(i);
                if (inSlot.isEmpty()) {
                    int countBefore = stack.getCount();
                    stack = target.insertItem(i, stack, false);
                    if (stack.getCount() < countBefore) {
                        if (state != null) {
                            state.lastSlot = i;
                            state.firstEmptySlot = i + 1; // このスロットが埋まったため次へ進める
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
                            continue; // TTL内ならシミュレーション・抽出一切なしで即スキップ！
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

                TargetState targetState = TARGET_STATE_CACHE.computeIfAbsent(target, k -> new TargetState());
                ItemStack remainder = fastInsertItemStacked(target, actuallyExtracted, targetState);
                int actuallyMoved = actuallyExtracted.getCount() - remainder.getCount();

                if (actuallyMoved > 0) {
                    movedTotal += actuallyMoved;
                    if (receivedHandlers != null) {
                        receivedHandlers.add(target);
                    }
                    // 重要: target の拒絶キャッシュを消してはならない！
                    // アイテムが入ったことで target の空きは減少したため、以前拒絶されたアイテムが入るようになることはない。
                    // 逆に、sourceHandler からアイテムが引き抜かれたため、sourceHandler 側の空きが増加した可能性がある。
                    // そのため、sourceHandler に対する拒絶キャッシュと状態キャッシュのみをクリアする！
                    if (persistentCache != null) {
                        persistentCache.remove(sourceHandler);
                    }
                    TARGET_STATE_CACHE.remove(sourceHandler);
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
