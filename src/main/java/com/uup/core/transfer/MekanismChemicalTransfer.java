package com.uup.core.transfer;

import com.uup.logging.UUPLogger;
import mekanism.api.Action;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import mekanism.api.chemical.gas.IGasHandler;
import mekanism.api.chemical.infuse.IInfusionHandler;
import mekanism.api.chemical.pigment.IPigmentHandler;
import mekanism.api.chemical.slurry.ISlurryHandler;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

public class MekanismChemicalTransfer {

    public static boolean hasChemicalCapability(BlockEntity be, Direction side) {
        if (be == null) return false;
        return be.getCapability(Capabilities.GAS_HANDLER, side).isPresent()
                || be.getCapability(Capabilities.INFUSION_HANDLER, side).isPresent()
                || be.getCapability(Capabilities.PIGMENT_HANDLER, side).isPresent()
                || be.getCapability(Capabilities.SLURRY_HANDLER, side).isPresent();
    }

    public static void collectCapabilities(
            BlockEntity be,
            Direction side,
            boolean isInsert,
            boolean isExtract,
            List<Object> gasInj, List<Object> gasExt,
            List<Object> infInj, List<Object> infExt,
            List<Object> pigInj, List<Object> pigExt,
            List<Object> sluInj, List<Object> sluExt
    ) {
        if (be == null) return;

        be.getCapability(Capabilities.GAS_HANDLER, side).ifPresent(h -> {
            if (isInsert && !gasInj.contains(h)) gasInj.add(h);
            if (isExtract && !gasExt.contains(h)) gasExt.add(h);
        });

        be.getCapability(Capabilities.INFUSION_HANDLER, side).ifPresent(h -> {
            if (isInsert && !infInj.contains(h)) infInj.add(h);
            if (isExtract && !infExt.contains(h)) infExt.add(h);
        });

        be.getCapability(Capabilities.PIGMENT_HANDLER, side).ifPresent(h -> {
            if (isInsert && !pigInj.contains(h)) pigInj.add(h);
            if (isExtract && !pigExt.contains(h)) pigExt.add(h);
        });

        be.getCapability(Capabilities.SLURRY_HANDLER, side).ifPresent(h -> {
            if (isInsert && !sluInj.contains(h)) sluInj.add(h);
            if (isExtract && !sluExt.contains(h)) sluExt.add(h);
        });
    }

    @SuppressWarnings("unchecked")
    public static <C extends Chemical<C>, S extends ChemicalStack<C>> long transferChemical(
            IChemicalHandler<C, S> source,
            List<IChemicalHandler<C, S>> targets,
            int overclocks,
            String chemType
    ) {
        if (source == null || targets == null || targets.isEmpty()) {
            return 0;
        }

        long movedTotal = 0;
        int tanks = source.getTanks();

        for (int tank = 0; tank < tanks; tank++) {
            S inTank = source.getChemicalInTank(tank);
            if (inTank.isEmpty()) continue;

            // Direct 1-Pass to all targets (O(Targets))
            for (IChemicalHandler<C, S> target : targets) {
                if (target == source) continue;

                // 1. Simulate extracting available amount
                S sample = source.extractChemical(tank, Long.MAX_VALUE, Action.SIMULATE);
                if (sample.isEmpty()) break;

                // 2. Check target's immediate intake demand (Simulation)
                S remainder = target.insertChemical(sample, Action.SIMULATE);
                long canAccept = sample.getAmount() - (remainder.isEmpty() ? 0 : remainder.getAmount());
                if (canAccept <= 0) continue;

                // 3. Extract exact accepted amount from source (Execution)
                S actuallyExtracted = source.extractChemical(tank, canAccept, Action.EXECUTE);
                if (actuallyExtracted.isEmpty()) break;

                // 4. Inject into target (Execution)
                S unaccepted = target.insertChemical(actuallyExtracted, Action.EXECUTE);
                long accepted = actuallyExtracted.getAmount() - (unaccepted.isEmpty() ? 0 : unaccepted.getAmount());
                movedTotal += accepted;

                // Rollback if any unexpected unaccepted amount
                if (!unaccepted.isEmpty() && unaccepted.getAmount() > 0) {
                    source.insertChemical(tank, unaccepted, Action.EXECUTE);
                }
            }
        }

        if (movedTotal > 0) {
            UUPLogger.logTransfer(chemType, movedTotal, "Mek_Source", "Mek_Target");
        }
        return movedTotal;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void processTransfers(
            List<Object> gasExt, List<Object> gasInj,
            List<Object> infExt, List<Object> infInj,
            List<Object> pigExt, List<Object> pigInj,
            List<Object> sluExt, List<Object> sluInj,
            int overclocks
    ) {
        for (Object ext : gasExt) {
            transferChemical((IGasHandler) ext, (List) gasInj, overclocks, "MEK_GAS");
        }
        for (Object ext : infExt) {
            transferChemical((IInfusionHandler) ext, (List) infInj, overclocks, "MEK_INFUSION");
        }
        for (Object ext : pigExt) {
            transferChemical((IPigmentHandler) ext, (List) pigInj, overclocks, "MEK_PIGMENT");
        }
        for (Object ext : sluExt) {
            transferChemical((ISlurryHandler) ext, (List) sluInj, overclocks, "MEK_SLURRY");
        }
    }
}
