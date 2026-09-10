package com.uup.core.network;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fml.ModList;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility to inspect and cache side configuration and slot access rules of connected machines.
 *
 * Ensures:
 * 1. Machine side configuration (e.g. Mekanism Side Config, Furnace sided inv) takes precedence over pipe settings.
 * 2. General storage without configuration (Chests, Barrels, Drawers) defaults to pipe settings.
 * 3. Machine outputs are extracted ONLY from processed/finished output slots, preventing premature extraction of raw inputs.
 * 4. Ultra-fast nanosecond O(1) caching to completely avoid ticking lag.
 */
public class MachineSideDetector {

    public record SideAccess(boolean canInsert, boolean canExtract, boolean isConfiguredMachine) {
        public static final SideAccess PASS_THROUGH = new SideAccess(true, true, false); // General storage
        public static final SideAccess DENIED = new SideAccess(false, false, true);       // Disabled side
        public static final SideAccess INPUT_ONLY = new SideAccess(true, false, true);    // Input only
        public static final SideAccess OUTPUT_ONLY = new SideAccess(false, true, true);   // Output only
        public static final SideAccess BOTH = new SideAccess(true, true, true);           // Input and Output
    }

    private record CacheKey(BlockEntity be, Direction side) {}

    private static final Map<CacheKey, SideAccess> SIDE_ACCESS_CACHE = new ConcurrentHashMap<>();

    private static boolean isMekanismLoaded() {
        try {
            return ModList.get() != null && ModList.get().isLoaded("mekanism");
        } catch (Throwable t) {
            return false;
        }
    }

    public static void clearCache() {
        SIDE_ACCESS_CACHE.clear();
    }

    /**
     * Obtains the allowed access (Insert / Extract) for a given block entity and connection side.
     * Results are cached for nanosecond O(1) repeated checks without lag.
     */
    public static SideAccess getSideAccess(BlockEntity be, Direction side) {
        if (be == null) return SideAccess.DENIED;

        CacheKey key = new CacheKey(be, side);
        SideAccess cached = SIDE_ACCESS_CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        SideAccess evaluated = evaluateSideAccess(be, side);
        SIDE_ACCESS_CACHE.put(key, evaluated);
        return evaluated;
    }

    private static SideAccess evaluateSideAccess(BlockEntity be, Direction side) {
        // 1. Trash cans and void receptacles always accept items/fluids from any side unconditionally
        if (StorageDetector.isTrashCan(be)) {
            return SideAccess.PASS_THROUGH;
        }

        // 2. Storage blocks (Chests, Barrels, Drawers) that have NO sided machine configuration
        if (StorageDetector.isStorage(be)) {
            if (!isMekanismLoaded() || !MekanismHelper.isMekanismMachine(be)) {
                return SideAccess.PASS_THROUGH;
            }
        }

        // 2. Mekanism machines with Side Configuration
        if (isMekanismLoaded() && MekanismHelper.isMekanismMachine(be)) {
            return MekanismHelper.getMekanismSideAccess(be, side);
        }

        // 3. Vanilla Furnaces (Smelter, Blast Furnace, Smoker)
        if (be instanceof AbstractFurnaceBlockEntity) {
            if (side == Direction.DOWN) {
                return SideAccess.OUTPUT_ONLY; // Bottom side is finished product slot (slot 2)
            } else {
                return SideAccess.INPUT_ONLY;  // Top/Sides are input materials (slot 0) or fuel (slot 1)
            }
        }

        // 4. Other processing machines (not storage): check if sided ITEM_HANDLER is exposed
        if (side != null) {
            var capOpt = be.getCapability(ForgeCapabilities.ITEM_HANDLER, side);
            if (!capOpt.isPresent()) {
                return SideAccess.DENIED;
            }
            return SideAccess.BOTH;
        }

        return SideAccess.PASS_THROUGH;
    }

    /**
     * Determines effective insertion permission combining pipe mode and machine side config.
     * Machine side configuration takes strict precedence over pipe mode for machines.
     */
    public static boolean isEffectiveInsert(TransferMode pipeMode, SideAccess access) {
        if (pipeMode == TransferMode.DISABLED) return false;
        if (!access.isConfiguredMachine()) {
            return pipeMode == TransferMode.INSERT || pipeMode == TransferMode.BOTH;
        }
        // Machine priority: only insert if machine allows input on this side
        if (!access.canInsert()) return false;
        return pipeMode == TransferMode.INSERT || pipeMode == TransferMode.BOTH;
    }

    /**
     * Determines effective extraction permission combining pipe mode and machine side config.
     * Machine side configuration takes strict precedence over pipe mode for machines.
     */
    public static boolean isEffectiveExtract(TransferMode pipeMode, SideAccess access) {
        if (pipeMode == TransferMode.DISABLED) return false;
        if (!access.isConfiguredMachine()) {
            return pipeMode == TransferMode.EXTRACT || pipeMode == TransferMode.BOTH;
        }
        // Machine priority: only extract if machine allows output on this side
        if (!access.canExtract()) return false;
        return pipeMode == TransferMode.EXTRACT || pipeMode == TransferMode.BOTH;
    }

    /**
     * Checks if the machine has any finished products ready in its output slots.
     * Allows skipping entire extraction loops in O(1) if no finished items are present.
     */
    public static boolean hasFinishedOutput(BlockEntity be, Direction side) {
        if (be == null) return false;
        if (StorageDetector.isStorage(be)) return true; // Chests always allowed if items exist

        if (isMekanismLoaded() && MekanismHelper.isMekanismMachine(be)) {
            return MekanismHelper.hasMachineOutput(be, side);
        }

        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            return !furnace.getItem(2).isEmpty(); // Slot 2 is finished item
        }

        return true;
    }

    /**
     * Checks whether a specific slot in a block entity is a finished output slot.
     * Strictly blocks extraction from raw input slots (e.g. cobblestone in a factory).
     */
    public static boolean isOutputSlot(BlockEntity be, Direction side, int slotIndex, int totalSlots) {
        if (be == null) return true;
        if (StorageDetector.isStorage(be)) return true; // General storage: all slots extractable

        if (isMekanismLoaded() && MekanismHelper.isMekanismFactory(be)) {
            return MekanismHelper.isFactoryOutputSlot(be, side, slotIndex, totalSlots);
        }

        if (be instanceof AbstractFurnaceBlockEntity) {
            return slotIndex == 2; // Slot 2 is output; slot 0 (input) and slot 1 (fuel) are never extracted
        }

        return true;
    }

    /**
     * Isolated helper class for Mekanism integration to avoid ClassNotFoundException when Mekanism is absent.
     */
    private static class MekanismHelper {

        static boolean isMekanismMachine(BlockEntity be) {
            return be instanceof mekanism.common.tile.interfaces.ISideConfiguration;
        }

        static boolean isMekanismFactory(BlockEntity be) {
            return be instanceof mekanism.common.tile.factory.TileEntityFactory<?>;
        }

        static SideAccess getMekanismSideAccess(BlockEntity be, Direction side) {
            if (!(be instanceof mekanism.common.tile.interfaces.ISideConfiguration sideConfig)) {
                return SideAccess.PASS_THROUGH;
            }
            mekanism.common.tile.component.TileComponentConfig config = sideConfig.getConfig();
            if (config == null) return SideAccess.DENIED;

            mekanism.common.tile.component.config.slot.ISlotInfo slotInfo =
                    config.getSlotInfo(mekanism.common.lib.transmitter.TransmissionType.ITEM, side);
            if (slotInfo == null || !slotInfo.isEnabled()) {
                return SideAccess.DENIED;
            }

            boolean canIn = slotInfo.canInput();
            boolean canOut = slotInfo.canOutput();
            if (canIn && canOut) return SideAccess.BOTH;
            if (canIn) return SideAccess.INPUT_ONLY;
            if (canOut) return SideAccess.OUTPUT_ONLY;
            return SideAccess.DENIED;
        }

        static boolean hasMachineOutput(BlockEntity be, Direction side) {
            if (!(be instanceof mekanism.common.tile.interfaces.ISideConfiguration sideConfig)) {
                return true;
            }
            mekanism.common.tile.component.TileComponentConfig config = sideConfig.getConfig();
            if (config == null) return true;

            mekanism.common.tile.component.config.slot.ISlotInfo slotInfo =
                    config.getSlotInfo(mekanism.common.lib.transmitter.TransmissionType.ITEM, side);
            if (slotInfo instanceof mekanism.common.tile.component.config.slot.InventorySlotInfo invSlotInfo) {
                for (mekanism.api.inventory.IInventorySlot slot : invSlotInfo.getSlots()) {
                    if (!slot.isEmpty()) {
                        return true;
                    }
                }
                return false;
            }
            return true;
        }

        static boolean isFactoryOutputSlot(BlockEntity be, Direction side, int slotIndex, int totalSlots) {
            if (!(be instanceof mekanism.common.tile.factory.TileEntityFactory<?> factory)) {
                return true;
            }
            int processes = factory.tier.processes;

            // Check side configuration data type
            if (be instanceof mekanism.common.tile.interfaces.ISideConfiguration sideConfig) {
                mekanism.common.tile.component.TileComponentConfig config = sideConfig.getConfig();
                if (config != null && side != null) {
                    mekanism.api.RelativeSide relSide = mekanism.api.RelativeSide.fromDirections(factory.getDirection(), side);
                    mekanism.common.tile.component.config.DataType dt =
                            config.getDataType(mekanism.common.lib.transmitter.TransmissionType.ITEM, relSide);
                    if (dt == mekanism.common.tile.component.config.DataType.OUTPUT
                            || dt == mekanism.common.tile.component.config.DataType.OUTPUT_1
                            || dt == mekanism.common.tile.component.config.DataType.OUTPUT_2) {
                        return true; // Exclusively output slots exposed on this side
                    }
                    if (dt == mekanism.common.tile.component.config.DataType.INPUT_OUTPUT) {
                        // Input slots are slots [0..processes-1], Output slots are slots [processes..2*processes-1]
                        return slotIndex >= processes;
                    }
                }
            }

            // Fallback for combined item handlers: output slots are placed after input slots
            if (totalSlots > processes) {
                return slotIndex >= processes;
            }
            return true;
        }
    }
}
