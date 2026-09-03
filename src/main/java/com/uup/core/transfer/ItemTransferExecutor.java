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
        if (sourceHandler == null || targetHandlers.isEmpty()) {
            return 0;
        }

        int baseRate = ModConfig.COMMON != null && ModConfig.COMMON.baseItemTransferRate != null 
                ? ModConfig.COMMON.baseItemTransferRate.get() : Integer.MAX_VALUE;
        double multiplier = ModConfig.COMMON != null && ModConfig.COMMON.overclockItemMultiplier != null 
                ? ModConfig.COMMON.overclockItemMultiplier.get() : 4.0;
        
        long maxToMove = (long) (baseRate * Math.pow(multiplier, Math.min(overclocks, 16)));
        if (maxToMove <= 0) maxToMove = Integer.MAX_VALUE;

        long movedTotal = 0;

        for (int slot = 0; slot < sourceHandler.getSlots() && movedTotal < maxToMove; slot++) {
            ItemStack inSlot = sourceHandler.getStackInSlot(slot);
            if (inSlot.isEmpty()) continue;

            int extractLimit = (int) Math.min(inSlot.getCount(), maxToMove - movedTotal);
            ItemStack simulatedExtract = sourceHandler.extractItem(slot, extractLimit, true);
            if (simulatedExtract.isEmpty()) continue;

            // Find how much can actually be inserted into valid targets (skipping sourceHandler)
            ItemStack toInsert = simulatedExtract.copy();
            int canAcceptTotal = 0;

            for (IItemHandler target : targetHandlers) {
                if (target == sourceHandler) continue;
                if (toInsert.isEmpty()) break;

                ItemStack remainder = ItemHandlerHelper.insertItemStacked(target, toInsert, true);
                int accepted = toInsert.getCount() - remainder.getCount();
                if (accepted > 0) {
                    canAcceptTotal += accepted;
                    toInsert.shrink(accepted);
                }
            }

            if (canAcceptTotal > 0) {
                // Actually extract the exact accepted amount from source
                ItemStack actuallyExtracted = sourceHandler.extractItem(slot, canAcceptTotal, false);
                if (!actuallyExtracted.isEmpty()) {
                    ItemStack movingStack = actuallyExtracted.copy();
                    for (IItemHandler target : targetHandlers) {
                        if (target == sourceHandler) continue;
                        if (movingStack.isEmpty()) break;
                        movingStack = ItemHandlerHelper.insertItemStacked(target, movingStack, false);
                    }
                    int actuallyMoved = actuallyExtracted.getCount() - movingStack.getCount();
                    movedTotal += actuallyMoved;
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
        if (storage == null || targetHandlers.isEmpty()) return;
        executeTransfer(storage.getItemBuffer(), targetHandlers, overclocks, "UUP_Controller_Buffer", "Network_Targets");
    }
}
