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

            for (IItemHandler target : targetHandlers) {
                if (target == sourceHandler) continue;
                if (movedTotal >= maxToMove) break;

                ItemStack currentInSlot = sourceHandler.getStackInSlot(slot);
                if (currentInSlot.isEmpty()) break;

                int currentLimit = (int) Math.min((long) currentInSlot.getCount(), maxToMove - movedTotal);
                if (currentLimit <= 0) break;

                // 1. Simulate extraction from source
                ItemStack simulatedExtract = sourceHandler.extractItem(slot, currentLimit, true);
                if (simulatedExtract.isEmpty()) break;

                // 2. Simulate insertion into target
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(target, simulatedExtract, true);
                int accepted = simulatedExtract.getCount() - remainder.getCount();
                if (accepted <= 0) continue;

                // 3. Execute extract and insert
                ItemStack actuallyExtracted = sourceHandler.extractItem(slot, accepted, false);
                if (!actuallyExtracted.isEmpty()) {
                    ItemStack insertedRemainder = ItemHandlerHelper.insertItemStacked(target, actuallyExtracted, false);
                    int actuallyMoved = actuallyExtracted.getCount() - insertedRemainder.getCount();
                    movedTotal += actuallyMoved;

                    // Rollback remainder if any
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
