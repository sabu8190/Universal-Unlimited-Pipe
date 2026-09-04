package com.uup.core.transfer;

import com.uup.config.ModConfig;
import com.uup.core.network.DirectBufferStorage;
import com.uup.logging.UUPLogger;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.List;

public class ItemTransferExecutor {

    public static long executeTransfer(
            IItemHandler sourceHandler,
            List<IItemHandler> targetHandlers,
            int overclocks,
            String sourceLabel,
            String targetLabel
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

        // [Opt 1] Fast-Path Cache for same-item consecutive slots
        ItemStack cachedItemType = ItemStack.EMPTY;
        IItemHandler cachedTarget = null;

        for (int slot = 0; slot < slots && movedTotal < maxToMove; slot++) {
            ItemStack inSlot = sourceHandler.getStackInSlot(slot);
            if (inSlot.isEmpty()) continue;

            int extractLimit = (int) Math.min(inSlot.getCount(), maxToMove - movedTotal);
            if (extractLimit <= 0) break;

            // Check if we can use Fast-Path (Same item type as previous successful target)
            boolean isSameItem = !cachedItemType.isEmpty() && ItemStack.isSameItemSameTags(inSlot, cachedItemType);

            if (isSameItem && cachedTarget != null && cachedTarget != sourceHandler) {
                // Fast-Path: Directly attempt transfer to the cached target without simulation
                ItemStack extracted = sourceHandler.extractItem(slot, extractLimit, false);
                if (!extracted.isEmpty()) {
                    ItemStack remainder = ItemHandlerHelper.insertItemStacked(cachedTarget, extracted, false);
                    int moved = extracted.getCount() - remainder.getCount();
                    movedTotal += moved;

                    if (!remainder.isEmpty()) {
                        // Cached target is now full/partially full, rollback remainder and invalidate cache
                        ItemHandlerHelper.insertItemStacked(sourceHandler, remainder, false);
                        cachedTarget = null;
                    } else {
                        // Successfully moved entire stack through Fast-Path! Continue to next slot
                        continue;
                    }
                }
            }

            // Normal Path: Iterate through targets with simulation
            for (IItemHandler target : targetHandlers) {
                if (target == sourceHandler) continue;
                if (movedTotal >= maxToMove) break;

                int currentLimit = (int) Math.min(sourceHandler.getStackInSlot(slot).getCount(), maxToMove - movedTotal);
                if (currentLimit <= 0) break;

                ItemStack simulatedExtract = sourceHandler.extractItem(slot, currentLimit, true);
                if (simulatedExtract.isEmpty()) break;

                ItemStack remainder = ItemHandlerHelper.insertItemStacked(target, simulatedExtract, true);
                int accepted = simulatedExtract.getCount() - remainder.getCount();
                if (accepted <= 0) continue;

                // Execute extract and insert
                ItemStack actuallyExtracted = sourceHandler.extractItem(slot, accepted, false);
                if (!actuallyExtracted.isEmpty()) {
                    ItemStack insertedRemainder = ItemHandlerHelper.insertItemStacked(target, actuallyExtracted, false);
                    int actuallyMoved = actuallyExtracted.getCount() - insertedRemainder.getCount();
                    movedTotal += actuallyMoved;

                    // Update Fast-Path Cache if target accepted items completely
                    if (insertedRemainder.isEmpty()) {
                        cachedItemType = actuallyExtracted.copy();
                        cachedTarget = target;
                    } else {
                        // Target became full, rollback remainder
                        ItemHandlerHelper.insertItemStacked(sourceHandler, insertedRemainder, false);
                        cachedTarget = null;
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
}
