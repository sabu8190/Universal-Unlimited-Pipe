package com.uup.core.transfer;

import com.uup.logging.UUPLogger;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.energy.IStrictEnergyHandler;
import mekanism.api.math.FloatingLong;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

public class MekanismEnergyTransfer {

    public static boolean hasStrictEnergyCapability(BlockEntity be, Direction side) {
        if (be == null) return false;
        return be.getCapability(Capabilities.STRICT_ENERGY, side).isPresent();
    }

    public static void collectCapabilities(
            BlockEntity be,
            Direction side,
            boolean isInsert,
            boolean isExtract,
            List<Object> injectors,
            List<Object> extractors
    ) {
        if (be == null) return;
        be.getCapability(Capabilities.STRICT_ENERGY, side).ifPresent(handler -> {
            if (isInsert && !injectors.contains(handler)) injectors.add(handler);
            if (isExtract && !extractors.contains(handler)) extractors.add(handler);
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void executeTransfer(
            IStrictEnergyHandler source,
            List<Object> targets,
            int overclocks
    ) {
        if (source == null || targets == null || targets.isEmpty()) return;

        FloatingLong available = source.extractEnergy(FloatingLong.MAX_VALUE, Action.SIMULATE);
        if (available.isZero()) return;

        FloatingLong movedTotal = FloatingLong.ZERO;

        for (Object targetObj : targets) {
            if (!(targetObj instanceof IStrictEnergyHandler target) || target == source) continue;

            // 1. Check target demand (Simulation)
            FloatingLong remainder = target.insertEnergy(available, Action.SIMULATE);
            FloatingLong canAccept = available.subtract(remainder);
            if (canAccept.isZero()) continue;

            // 2. Extract exact accepted amount from source (Execution)
            FloatingLong actuallyExtracted = source.extractEnergy(canAccept, Action.EXECUTE);
            if (actuallyExtracted.isZero()) break;

            // 3. Inject into target (Execution)
            FloatingLong unaccepted = target.insertEnergy(actuallyExtracted, Action.EXECUTE);
            FloatingLong accepted = actuallyExtracted.subtract(unaccepted);
            movedTotal = movedTotal.add(accepted);

            // Rollback if any unaccepted remainder
            if (!unaccepted.isZero()) {
                source.insertEnergy(unaccepted, Action.EXECUTE);
            }

            available = available.subtract(accepted);
            if (available.isZero()) break;
        }

        if (!movedTotal.isZero()) {
            UUPLogger.logTransfer("MEK_STRICT_ENERGY", movedTotal.longValue(), "Mek_Energy_Source", "Mek_Energy_Target");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void processTransfers(List<Object> extractors, List<Object> injectors, int overclocks) {
        for (Object ext : extractors) {
            if (ext instanceof IStrictEnergyHandler source) {
                executeTransfer(source, (List) injectors, overclocks);
            }
        }
    }
}
