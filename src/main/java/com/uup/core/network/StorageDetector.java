package com.uup.core.network;

import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.*;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility to classify whether a BlockEntity is a storage inventory (e.g. Chest, Barrel, Drawer, Tank)
 * or a processing machine / generator (e.g. Mekanism Smelting Factory, Furnace).
 *
 * Results are cached by class for nanosecond O(1) repeated checks.
 */
public class StorageDetector {

    private static final Map<Class<?>, Boolean> STORAGE_CLASS_CACHE = new ConcurrentHashMap<>();

    public static boolean isStorage(BlockEntity be) {
        if (be == null) return false;
        Class<?> clazz = be.getClass();
        Boolean cached = STORAGE_CLASS_CACHE.get(clazz);
        if (cached != null) {
            return cached;
        }

        boolean result = evaluateIsStorage(be);
        STORAGE_CLASS_CACHE.put(clazz, result);
        return result;
    }

    private static boolean evaluateIsStorage(BlockEntity be) {
        // 1. Explicit processing machines / furnaces to exclude
        if (be instanceof AbstractFurnaceBlockEntity
                || be instanceof BrewingStandBlockEntity) {
            return false;
        }

        String className = be.getClass().getName().toLowerCase(Locale.ROOT);

        // 2. Machine / Factory / Generator keywords (definitely not passive storage)
        if (className.contains("factory")
                || className.contains("machine")
                || className.contains("generator")
                || className.contains("smelter")
                || className.contains("crusher")
                || className.contains("enrich")
                || className.contains("purif")
                || className.contains("compressor")
                || className.contains("centrifuge")
                || className.contains("reactor")
                || className.contains("furnace")
                || className.contains("turbine")) {
            return false;
        }

        // 3. Vanilla explicit storage types
        if (be instanceof ChestBlockEntity
                || be instanceof BarrelBlockEntity
                || be instanceof ShulkerBoxBlockEntity
                || be instanceof HopperBlockEntity
                || be instanceof DispenserBlockEntity
                || be instanceof DropperBlockEntity
                || be instanceof ChiseledBookShelfBlockEntity) {
            return true;
        }

        // 4. Modded storage keywords
        if (className.contains("chest")
                || className.contains("barrel")
                || className.contains("drawer")
                || className.contains("bin")
                || className.contains("crate")
                || className.contains("vault")
                || className.contains("shulker")
                || className.contains("storage")
                || className.contains("cabinet")
                || className.contains("safe")
                || className.contains("tank")
                || className.contains("drum")
                || className.contains("battery")
                || className.contains("capacitor")
                || className.contains("energycube")
                || className.contains("interface")) {
            return true;
        }

        // 5. Generic Container instances that are not machines
        if (be instanceof Container) {
            return true;
        }

        return false;
    }

    public static void clearCache() {
        STORAGE_CLASS_CACHE.clear();
    }
}
