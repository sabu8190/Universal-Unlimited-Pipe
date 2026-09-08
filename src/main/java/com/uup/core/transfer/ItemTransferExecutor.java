package com.uup.core.transfer;

import com.uup.config.ModConfig;
import com.uup.core.network.DirectBufferStorage;
import com.uup.logging.UUPLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
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
        executeAllItemTransfers(extractors, injectors, overclocks, currentTick, persistentRejectionCache, null, null);
    }

    public static boolean isStorageBlockEntity(@Nullable BlockEntity be) {
        if (be == null) return false;
        if (be instanceof RandomizableContainerBlockEntity) return true;
        String name = be.getClass().getName().toLowerCase(Locale.ROOT);
        return name.contains("chest") || 
               name.contains("barrel") || 
               name.contains("storage") || 
               name.contains("drawer") || 
               name.contains("vault") || 
               name.contains("bin") ||
               name.contains("crate");
    }

    public static void executeAllItemTransfers(
            List<IItemHandler> extractors,
            List<IItemHandler> injectors,
            int overclocks,
            long currentTick,
            @Nullable Map<IItemHandler, Map<ItemKey, Long>> persistentRejectionCache,
            @Nullable Map<Object, BlockPos> handlerPositions,
            @Nullable Map<Object, Integer> handlerPriorities
    ) {
        executeAllItemTransfers(extractors, injectors, overclocks, currentTick, persistentRejectionCache, handlerPositions, handlerPriorities, null);
    }

    public static void executeAllItemTransfers(
            List<IItemHandler> extractors,
            List<IItemHandler> injectors,
            int overclocks,
            long currentTick,
            @Nullable Map<IItemHandler, Map<ItemKey, Long>> persistentRejectionCache,
            @Nullable Map<Object, BlockPos> handlerPositions,
            @Nullable Map<Object, Integer> handlerPriorities,
            @Nullable Map<Object, BlockEntity> handlerBlockEntities
    ) {
        if (extractors == null || extractors.isEmpty() || injectors == null || injectors.isEmpty()) {
            return;
        }

        Map<IItemHandler, Set<ItemKey>> sharedRejectedMap = new IdentityHashMap<>();
        Set<IItemHandler> receivedInThisTick = Collections.newSetFromMap(new IdentityHashMap<>());

        // 各 extractor ごとにソート済みターゲットリストをキャッシュ（同一Tick内で再利用）
        Map<IItemHandler, List<IItemHandler>> sortedTargetsCache = new IdentityHashMap<>();

        for (IItemHandler extractor : extractors) {
            if (extractor == null) continue;
            // ピンポン防止: 同一Tick内で既にアイテムを受け取ったインベントリからは搬出しない
            if (receivedInThisTick.contains(extractor)) {
                continue;
            }

            List<IItemHandler> sortedTargets = sortedTargetsCache.computeIfAbsent(extractor, ext -> {
                BlockEntity srcBe = handlerBlockEntities != null ? handlerBlockEntities.get(ext) : null;
                BlockPos srcPos = handlerPositions != null ? handlerPositions.get(ext) : null;

                List<IItemHandler> list = new ArrayList<>(injectors.size());
                for (IItemHandler target : injectors) {
                    if (target == null || target == ext) continue;

                    BlockEntity targetBe = handlerBlockEntities != null ? handlerBlockEntities.get(target) : null;

                    // [Crucial Fix 1: 同種機械ループの完全遮断]
                    // 製錬工場から出たアイテムを別の製錬工場に搬入しようとする10,000回の無駄走査を完全排除！
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
                        if (p1 != p2) return Integer.compare(p2, p1); // 優先度降順 (高い順)

                        // [Crucial Fix 2: ストレージ（保管庫）最優先]
                        // チェスト・バレルなどの純粋ストレージを加工機械よりも優先
                        BlockEntity be1 = handlerBlockEntities != null ? handlerBlockEntities.get(t1) : null;
                        BlockEntity be2 = handlerBlockEntities != null ? handlerBlockEntities.get(t2) : null;
                        boolean isStorage1 = isStorageBlockEntity(be1);
                        boolean isStorage2 = isStorageBlockEntity(be2);
                        if (isStorage1 != isStorage2) {
                            return isStorage1 ? -1 : 1;
                        }

                        // [Nearest-First: 搬出元から近い順]
                        if (srcPos != null && handlerPositions != null) {
                            BlockPos pos1 = handlerPositions.get(t1);
                            BlockPos pos2 = handlerPositions.get(t2);
                            if (pos1 != null && pos2 != null) {
                                double d1 = srcPos.distSqr(pos1);
                                double d2 = srcPos.distSqr(pos2);
                                int cmp = Double.compare(d1, d2);
                                if (cmp != 0) return cmp; // 距離昇順 (搬出元から近い順)
                                return pos1.compareTo(pos2); // 決定論的順序
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
        final Map<ItemKey, Integer> lastSlotByItem = new HashMap<>();
        int firstEmptySlot = 0;
    }

    private static final Map<IItemHandler, TargetState> TARGET_STATE_CACHE = 
            Collections.synchronizedMap(new WeakHashMap<>());

    // アイテムごとの優先ターゲット記憶 (Preferred Target Routing: 200ターゲットの総当たり走査を O(1) 直行化)
    private static final Map<ItemKey, IItemHandler> PREFERRED_TARGET_BY_ITEM = 
            Collections.synchronizedMap(new WeakHashMap<>());

    // 空インベントリスキップキャッシュ (Empty Source Cache: TTL 5 Tick = 0.25秒で無駄な全スロット走査を完全スキップ)
    private static final Map<IItemHandler, Long> EMPTY_SOURCE_CACHE = 
            Collections.synchronizedMap(new WeakHashMap<>());

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
        int slots = target.getSlots();
        if (slots <= 0) return stack;

        // [Fast Path 1: アイテム別 Last-Hit Direct Insert ($O(1)$)]
        // 複数アイテムが混在していても、アイテムごとに直前スロットを記憶しているため確実にヒット！
        if (state != null) {
            Integer lastSlotObj = state.lastSlotByItem.get(itemKey);
            if (lastSlotObj != null) {
                int lastSlot = lastSlotObj;
                if (lastSlot >= 0 && lastSlot < slots) {
                    ItemStack inLastSlot = target.getStackInSlot(lastSlot);
                    if (!inLastSlot.isEmpty() && canItemsStackFast(stack, inLastSlot)) {
                        int countBefore = stack.getCount();
                        stack = target.insertItem(lastSlot, stack, simulate);
                        if (stack.getCount() < countBefore) {
                            if (stack.isEmpty()) {
                                return ItemStack.EMPTY;
                            }
                        } else if (!simulate) {
                            // そのスロットが満杯になったためキャッシュから除外
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
                stack = target.insertItem(i, stack, simulate);
                if (stack.getCount() < countBefore) {
                    if (state != null && !simulate) {
                        state.lastSlotByItem.put(itemKey, i);
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

        // [Fast Path 3: 空きスロットへの挿入]
        // 走査開始位置を記録された空きスロットから開始することで、先頭の埋まっているスロットを完全スキップ
        int startSearchEmpty = (foundEmptySlot != -1) ? foundEmptySlot : firstEmpty;
        if (!stack.isEmpty() && startSearchEmpty < slots) {
            for (int i = startSearchEmpty; i < slots; i++) {
                ItemStack inSlot = target.getStackInSlot(i);
                if (inSlot.isEmpty()) {
                    int countBefore = stack.getCount();
                    stack = target.insertItem(i, stack, simulate);
                    if (stack.getCount() < countBefore) {
                        if (state != null && !simulate) {
                            state.lastSlotByItem.put(itemKey, i);
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

        // [Optimization A: Empty Source Cache Check]
        // 直前チェックで完全に空だったインベントリは 5 Tick (0.25秒) スキップして無駄な getStackInSlot を排除
        if (currentTick > 0) {
            Long emptyUntil = EMPTY_SOURCE_CACHE.get(sourceHandler);
            if (emptyUntil != null && currentTick < emptyUntil) {
                return 0;
            }
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

        boolean hasAnyItem = false;

        for (int slot = 0; slot < slots && movedTotal < maxToMove; slot++) {
            ItemStack inSlot = sourceHandler.getStackInSlot(slot);
            if (inSlot.isEmpty()) continue;
            hasAnyItem = true;

            ItemKey itemKey = ItemKey.of(inSlot);

            // [Optimization B: Preferred Target Routing]
            // このアイテム種別を直前に受け入れ成功したターゲットを優先して先頭に配置
            IItemHandler preferredTarget = PREFERRED_TARGET_BY_ITEM.get(itemKey);
            if (preferredTarget != null && (preferredTarget == sourceHandler || !validTargets.contains(preferredTarget))) {
                PREFERRED_TARGET_BY_ITEM.remove(itemKey);
                preferredTarget = null;
            }

            // 探索ターゲットリストの構築: preferredTarget があれば最優先で試行
            List<IItemHandler> targetsToTry;
            if (preferredTarget != null) {
                targetsToTry = new ArrayList<>(validTargets.size());
                targetsToTry.add(preferredTarget);
                for (IItemHandler t : validTargets) {
                    if (t != preferredTarget) {
                        targetsToTry.add(t);
                    }
                }
            } else {
                targetsToTry = validTargets;
            }

            for (IItemHandler target : targetsToTry) {
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

                // [Optimization 3: 事前シミュレーション確認による無駄抽出＆ロールバックの完全根絶]
                // ターゲットが確実に受け入れ可能な数量 (accepted) をシミュレーションで確認
                ItemStack probeStack = currentInSlot.copy();
                probeStack.setCount(currentLimit);

                TargetState targetState = TARGET_STATE_CACHE.computeIfAbsent(target, k -> new TargetState());
                ItemStack simRemainder = fastInsertItemStacked(target, probeStack, targetState, itemKey, true);
                int accepted = currentLimit - simRemainder.getCount();

                if (accepted <= 0) {
                    // 全く受け入れられない場合: ターゲットを拒絶キャッシュに登録
                    rejectedMap.computeIfAbsent(target, k -> new HashSet<>()).add(itemKey);
                    if (persistentCache != null) {
                        persistentCache.computeIfAbsent(target, k -> new HashMap<>()).put(itemKey, currentTick + 10L);
                    }
                    if (target == preferredTarget) {
                        PREFERRED_TARGET_BY_ITEM.remove(itemKey);
                    }
                    continue;
                }

                // 確実に受け入れられる分だけをソースから本番抽出！
                ItemStack actuallyExtracted = sourceHandler.extractItem(slot, accepted, false);
                if (actuallyExtracted.isEmpty()) break;

                // ターゲットへ本番挿入 (simulate=false)
                ItemStack realRemainder = fastInsertItemStacked(target, actuallyExtracted, targetState, itemKey, false);
                int actuallyMoved = actuallyExtracted.getCount() - realRemainder.getCount();

                if (actuallyMoved > 0) {
                    movedTotal += actuallyMoved;
                    PREFERRED_TARGET_BY_ITEM.put(itemKey, target); // 優先ルーティングを記憶！
                    if (receivedHandlers != null) {
                        receivedHandlers.add(target);
                    }
                    if (persistentCache != null) {
                        persistentCache.remove(sourceHandler);
                    }
                    TARGET_STATE_CACHE.remove(sourceHandler);
                    EMPTY_SOURCE_CACHE.remove(target); // ターゲットにアイテムが入ったため空キャッシュ解除
                }

                // 万が一の極小余りのみロールバック (事前確認済みのため通常は発生しない)
                if (!realRemainder.isEmpty()) {
                    sourceHandler.insertItem(slot, realRemainder, false);
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

        // ソースインベントリが完全に空だった場合、次回の走査を 5 Tick (0.25秒) スキップ
        if (!hasAnyItem && currentTick > 0) {
            EMPTY_SOURCE_CACHE.put(sourceHandler, currentTick + 5L);
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
