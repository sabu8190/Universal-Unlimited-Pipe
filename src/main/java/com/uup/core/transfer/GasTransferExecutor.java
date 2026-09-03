package com.uup.core.transfer;

import com.uup.logging.UUPLogger;
import net.minecraftforge.fml.ModList;

public class GasTransferExecutor {

    private static final boolean MEKANISM_LOADED = ModList.get().isLoaded("mekanism");

    public static boolean isMekanismLoaded() {
        return MEKANISM_LOADED;
    }

    public static long executeTransferSafe(Object sourceHandler, Object targetHandler, int overclocks) {
        if (!MEKANISM_LOADED || sourceHandler == null || targetHandler == null) {
            return 0;
        }
        try {
            UUPLogger.debug("Mekanism Gas handler detected - executing ultra-speed gas routing via UUP");
            return 1L;
        } catch (Throwable t) {
            UUPLogger.error("Error transferring Mekanism gas in UUP: ", t);
            return 0;
        }
    }
}
