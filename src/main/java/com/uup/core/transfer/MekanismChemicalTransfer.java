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

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void dispatchInternalBuffer(
            MekanismBuffer buffer,
            List<Object> gasInj, List<Object> infInj, List<Object> pigInj, List<Object> sluInj,
            int overclocks
    ) {
        if (buffer == null) return;
        if (gasInj != null && !gasInj.isEmpty()) {
            transferChemical(buffer.gasHandler, (List) gasInj, overclocks, "UUP_Controller_Gas_Buffer");
        }
        if (infInj != null && !infInj.isEmpty()) {
            transferChemical(buffer.infusionHandler, (List) infInj, overclocks, "UUP_Controller_Infusion_Buffer");
        }
        if (pigInj != null && !pigInj.isEmpty()) {
            transferChemical(buffer.pigmentHandler, (List) pigInj, overclocks, "UUP_Controller_Pigment_Buffer");
        }
        if (sluInj != null && !sluInj.isEmpty()) {
            transferChemical(buffer.slurryHandler, (List) sluInj, overclocks, "UUP_Controller_Slurry_Buffer");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void ingestToInternalBuffer(
            MekanismBuffer buffer,
            List<Object> gasExt, List<Object> infExt, List<Object> pigExt, List<Object> sluExt,
            int overclocks
    ) {
        if (buffer == null) return;
        if (gasExt != null && !gasExt.isEmpty()) {
            List<IGasHandler> target = List.of(buffer.gasHandler);
            for (Object ext : gasExt) {
                transferChemical((IGasHandler) ext, (List) target, overclocks, "Mek_Gas_Ingest");
            }
        }
        if (infExt != null && !infExt.isEmpty()) {
            List<IInfusionHandler> target = List.of(buffer.infusionHandler);
            for (Object ext : infExt) {
                transferChemical((IInfusionHandler) ext, (List) target, overclocks, "Mek_Infusion_Ingest");
            }
        }
        if (pigExt != null && !pigExt.isEmpty()) {
            List<IPigmentHandler> target = List.of(buffer.pigmentHandler);
            for (Object ext : pigExt) {
                transferChemical((IPigmentHandler) ext, (List) target, overclocks, "Mek_Pigment_Ingest");
            }
        }
        if (sluExt != null && !sluExt.isEmpty()) {
            List<ISlurryHandler> target = List.of(buffer.slurryHandler);
            for (Object ext : sluExt) {
                transferChemical((ISlurryHandler) ext, (List) target, overclocks, "Mek_Slurry_Ingest");
            }
        }
    }

    public static class MekanismBuffer {
        public final mekanism.api.chemical.gas.IGasTank gasTank = mekanism.api.chemical.ChemicalTankBuilder.GAS.createAllValid(Long.MAX_VALUE, null);
        public final mekanism.api.chemical.infuse.IInfusionTank infusionTank = mekanism.api.chemical.ChemicalTankBuilder.INFUSION.createAllValid(Long.MAX_VALUE, null);
        public final mekanism.api.chemical.pigment.IPigmentTank pigmentTank = mekanism.api.chemical.ChemicalTankBuilder.PIGMENT.createAllValid(Long.MAX_VALUE, null);
        public final mekanism.api.chemical.slurry.ISlurryTank slurryTank = mekanism.api.chemical.ChemicalTankBuilder.SLURRY.createAllValid(Long.MAX_VALUE, null);

        public final IGasHandler gasHandler = new IGasHandler() {
            @Override public int getTanks() { return 1; }
            @Override public mekanism.api.chemical.gas.GasStack getChemicalInTank(int tank) { return tank == 0 ? gasTank.getStack() : mekanism.api.chemical.gas.GasStack.EMPTY; }
            @Override public void setChemicalInTank(int tank, mekanism.api.chemical.gas.GasStack stack) { if (tank == 0) gasTank.setStack(stack); }
            @Override public long getTankCapacity(int tank) { return tank == 0 ? gasTank.getCapacity() : 0; }
            @Override public boolean isValid(int tank, mekanism.api.chemical.gas.GasStack stack) { return tank == 0 && gasTank.isValid(stack); }
            @Override public mekanism.api.chemical.gas.GasStack insertChemical(int tank, mekanism.api.chemical.gas.GasStack stack, Action action) {
                return tank == 0 ? gasTank.insert(stack, action, mekanism.api.AutomationType.EXTERNAL) : stack;
            }
            @Override public mekanism.api.chemical.gas.GasStack extractChemical(int tank, long amount, Action action) {
                return tank == 0 ? gasTank.extract(amount, action, mekanism.api.AutomationType.EXTERNAL) : mekanism.api.chemical.gas.GasStack.EMPTY;
            }
        };

        public final IInfusionHandler infusionHandler = new IInfusionHandler() {
            @Override public int getTanks() { return 1; }
            @Override public mekanism.api.chemical.infuse.InfusionStack getChemicalInTank(int tank) { return tank == 0 ? infusionTank.getStack() : mekanism.api.chemical.infuse.InfusionStack.EMPTY; }
            @Override public void setChemicalInTank(int tank, mekanism.api.chemical.infuse.InfusionStack stack) { if (tank == 0) infusionTank.setStack(stack); }
            @Override public long getTankCapacity(int tank) { return tank == 0 ? infusionTank.getCapacity() : 0; }
            @Override public boolean isValid(int tank, mekanism.api.chemical.infuse.InfusionStack stack) { return tank == 0 && infusionTank.isValid(stack); }
            @Override public mekanism.api.chemical.infuse.InfusionStack insertChemical(int tank, mekanism.api.chemical.infuse.InfusionStack stack, Action action) {
                return tank == 0 ? infusionTank.insert(stack, action, mekanism.api.AutomationType.EXTERNAL) : stack;
            }
            @Override public mekanism.api.chemical.infuse.InfusionStack extractChemical(int tank, long amount, Action action) {
                return tank == 0 ? infusionTank.extract(amount, action, mekanism.api.AutomationType.EXTERNAL) : mekanism.api.chemical.infuse.InfusionStack.EMPTY;
            }
        };

        public final IPigmentHandler pigmentHandler = new IPigmentHandler() {
            @Override public int getTanks() { return 1; }
            @Override public mekanism.api.chemical.pigment.PigmentStack getChemicalInTank(int tank) { return tank == 0 ? pigmentTank.getStack() : mekanism.api.chemical.pigment.PigmentStack.EMPTY; }
            @Override public void setChemicalInTank(int tank, mekanism.api.chemical.pigment.PigmentStack stack) { if (tank == 0) pigmentTank.setStack(stack); }
            @Override public long getTankCapacity(int tank) { return tank == 0 ? pigmentTank.getCapacity() : 0; }
            @Override public boolean isValid(int tank, mekanism.api.chemical.pigment.PigmentStack stack) { return tank == 0 && pigmentTank.isValid(stack); }
            @Override public mekanism.api.chemical.pigment.PigmentStack insertChemical(int tank, mekanism.api.chemical.pigment.PigmentStack stack, Action action) {
                return tank == 0 ? pigmentTank.insert(stack, action, mekanism.api.AutomationType.EXTERNAL) : stack;
            }
            @Override public mekanism.api.chemical.pigment.PigmentStack extractChemical(int tank, long amount, Action action) {
                return tank == 0 ? pigmentTank.extract(amount, action, mekanism.api.AutomationType.EXTERNAL) : mekanism.api.chemical.pigment.PigmentStack.EMPTY;
            }
        };

        public final ISlurryHandler slurryHandler = new ISlurryHandler() {
            @Override public int getTanks() { return 1; }
            @Override public mekanism.api.chemical.slurry.SlurryStack getChemicalInTank(int tank) { return tank == 0 ? slurryTank.getStack() : mekanism.api.chemical.slurry.SlurryStack.EMPTY; }
            @Override public void setChemicalInTank(int tank, mekanism.api.chemical.slurry.SlurryStack stack) { if (tank == 0) slurryTank.setStack(stack); }
            @Override public long getTankCapacity(int tank) { return tank == 0 ? slurryTank.getCapacity() : 0; }
            @Override public boolean isValid(int tank, mekanism.api.chemical.slurry.SlurryStack stack) { return tank == 0 && slurryTank.isValid(stack); }
            @Override public mekanism.api.chemical.slurry.SlurryStack insertChemical(int tank, mekanism.api.chemical.slurry.SlurryStack stack, Action action) {
                return tank == 0 ? slurryTank.insert(stack, action, mekanism.api.AutomationType.EXTERNAL) : stack;
            }
            @Override public mekanism.api.chemical.slurry.SlurryStack extractChemical(int tank, long amount, Action action) {
                return tank == 0 ? slurryTank.extract(amount, action, mekanism.api.AutomationType.EXTERNAL) : mekanism.api.chemical.slurry.SlurryStack.EMPTY;
            }
        };

        public final mekanism.common.capabilities.energy.BasicEnergyContainer energyContainer =
                mekanism.common.capabilities.energy.BasicEnergyContainer.create(mekanism.api.math.FloatingLong.MAX_VALUE,
                        mekanism.common.capabilities.energy.BasicEnergyContainer.alwaysTrue,
                        mekanism.common.capabilities.energy.BasicEnergyContainer.alwaysTrue, null);

        public final mekanism.api.energy.IStrictEnergyHandler energyHandler = new mekanism.api.energy.IStrictEnergyHandler() {
            @Override public int getEnergyContainerCount() { return 1; }
            @Override public mekanism.api.math.FloatingLong getEnergy(int container) { return container == 0 ? energyContainer.getEnergy() : mekanism.api.math.FloatingLong.ZERO; }
            @Override public void setEnergy(int container, mekanism.api.math.FloatingLong energy) { if (container == 0) energyContainer.setEnergy(energy); }
            @Override public mekanism.api.math.FloatingLong getMaxEnergy(int container) { return container == 0 ? energyContainer.getMaxEnergy() : mekanism.api.math.FloatingLong.ZERO; }
            @Override public mekanism.api.math.FloatingLong getNeededEnergy(int container) { return container == 0 ? energyContainer.getNeeded() : mekanism.api.math.FloatingLong.ZERO; }
            @Override public mekanism.api.math.FloatingLong insertEnergy(int container, mekanism.api.math.FloatingLong amount, Action action) {
                return container == 0 ? energyContainer.insert(amount, action, mekanism.api.AutomationType.EXTERNAL) : amount;
            }
            @Override public mekanism.api.math.FloatingLong extractEnergy(int container, mekanism.api.math.FloatingLong amount, Action action) {
                return container == 0 ? energyContainer.extract(amount, action, mekanism.api.AutomationType.EXTERNAL) : mekanism.api.math.FloatingLong.ZERO;
            }
        };

        public final net.minecraftforge.common.util.LazyOptional<IGasHandler> gasOpt = net.minecraftforge.common.util.LazyOptional.of(() -> gasHandler);
        public final net.minecraftforge.common.util.LazyOptional<IInfusionHandler> infusionOpt = net.minecraftforge.common.util.LazyOptional.of(() -> infusionHandler);
        public final net.minecraftforge.common.util.LazyOptional<IPigmentHandler> pigmentOpt = net.minecraftforge.common.util.LazyOptional.of(() -> pigmentHandler);
        public final net.minecraftforge.common.util.LazyOptional<ISlurryHandler> slurryOpt = net.minecraftforge.common.util.LazyOptional.of(() -> slurryHandler);
        public final net.minecraftforge.common.util.LazyOptional<mekanism.api.energy.IStrictEnergyHandler> energyOpt = net.minecraftforge.common.util.LazyOptional.of(() -> energyHandler);

        public void invalidate() {
            gasOpt.invalidate();
            infusionOpt.invalidate();
            pigmentOpt.invalidate();
            slurryOpt.invalidate();
            energyOpt.invalidate();
        }

        public net.minecraft.nbt.CompoundTag serializeNBT() {
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.put("Gas", gasTank.serializeNBT());
            tag.put("Infusion", infusionTank.serializeNBT());
            tag.put("Pigment", pigmentTank.serializeNBT());
            tag.put("Slurry", slurryTank.serializeNBT());
            tag.put("Energy", energyContainer.serializeNBT());
            return tag;
        }

        public void deserializeNBT(net.minecraft.nbt.CompoundTag tag) {
            if (tag.contains("Gas")) gasTank.deserializeNBT(tag.getCompound("Gas"));
            if (tag.contains("Infusion")) infusionTank.deserializeNBT(tag.getCompound("Infusion"));
            if (tag.contains("Pigment")) pigmentTank.deserializeNBT(tag.getCompound("Pigment"));
            if (tag.contains("Slurry")) slurryTank.deserializeNBT(tag.getCompound("Slurry"));
            if (tag.contains("Energy")) energyContainer.deserializeNBT(tag.getCompound("Energy"));
        }

        @SuppressWarnings("unchecked")
        public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(net.minecraftforge.common.capabilities.Capability<T> cap) {
            if (cap == Capabilities.GAS_HANDLER) return (net.minecraftforge.common.util.LazyOptional<T>) gasOpt;
            if (cap == Capabilities.INFUSION_HANDLER) return (net.minecraftforge.common.util.LazyOptional<T>) infusionOpt;
            if (cap == Capabilities.PIGMENT_HANDLER) return (net.minecraftforge.common.util.LazyOptional<T>) pigmentOpt;
            if (cap == Capabilities.SLURRY_HANDLER) return (net.minecraftforge.common.util.LazyOptional<T>) slurryOpt;
            if (cap == Capabilities.STRICT_ENERGY) return (net.minecraftforge.common.util.LazyOptional<T>) energyOpt;
            return net.minecraftforge.common.util.LazyOptional.empty();
        }
    }
}
