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

                // 1. ソースからの抽出シミュレーション
                ItemStack simulatedExtract = sourceHandler.extractItem(slot, currentLimit, true);
                if (simulatedExtract.isEmpty()) break;

                // 2. ターゲットへの挿入シミュレーション
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(target, simulatedExtract, true);
                int accepted = simulatedExtract.getCount() - remainder.getCount();
                if (accepted <= 0) {
                    // [Optimization 3] 受け入れ拒否されたアイテムをTick内＆TTLキャッシュに登録
                    rejectedMap.computeIfAbsent(target, k -> new HashSet<>()).add(itemKey);
                    if (persistentCache != null) {
                        persistentCache.computeIfAbsent(target, k -> new HashMap<>()).put(itemKey, currentTick + 10L);
                    }
                    continue;
                }

                // 3. 実際の抽出と挿入を実行
                ItemStack actuallyExtracted = sourceHandler.extractItem(slot, accepted, false);
                if (!actuallyExtracted.isEmpty()) {
                    ItemStack insertedRemainder = ItemHandlerHelper.insertItemStacked(target, actuallyExtracted, false);
                    int actuallyMoved = actuallyExtracted.getCount() - insertedRemainder.getCount();
                    movedTotal += actuallyMoved;

                    if (actuallyMoved > 0) {
                        if (receivedHandlers != null) {
                            receivedHandlers.add(target);
                        }
                        // [Optimization 4] 転送成功時: インベントリ空き状況が更新されたため即座にキャッシュ無効化（ゼロ遅延）
                        if (persistentCache != null) {
                            persistentCache.remove(target);
                            persistentCache.remove(sourceHandler);
                        }
                    }

                    // 挿入しきれなかった余りをソースにロールバック
                    if (!insertedRemainder.isEmpty()) {
                        ItemHandlerHelper.insertItemStacked(sourceHandler, insertedRemainder, false);
                    }
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
