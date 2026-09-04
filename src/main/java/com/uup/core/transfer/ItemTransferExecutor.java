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

        for (int slot = 0; slot < slots && movedTotal < maxToMove; slot++) {
            ItemStack inSlot = sourceHandler.getStackInSlot(slot);
            if (inSlot.isEmpty()) continue;

            // Direct 1-Pass distribution per target
            for (IItemHandler target : targetHandlers) {
                if (target == sourceHandler) continue;
                if (movedTotal >= maxToMove) break;

                int extractLimit = (int) Math.min(inSlot.getCount(), maxToMove - movedTotal);
                if (extractLimit <= 0) break;

                ItemStack simulatedExtract = sourceHandler.extractItem(slot, extractLimit, true);
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

                    // Rollback if any unexpected remainder
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
}
