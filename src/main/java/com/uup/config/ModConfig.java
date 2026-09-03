package com.uup.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig.Type;
import org.apache.commons.lang3.tuple.Pair;

public class ModConfig {

    public static final Common COMMON;
    public static final ForgeConfigSpec COMMON_SPEC;

    static {
        final Pair<Common, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON_SPEC = specPair.getRight();
        COMMON = specPair.getLeft();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(Type.COMMON, COMMON_SPEC, "uup-common.toml");
    }

    public static class Common {
        public final ForgeConfigSpec.IntValue baseItemTransferRate;
        public final ForgeConfigSpec.IntValue baseFluidTransferRate;
        public final ForgeConfigSpec.LongValue baseEnergyTransferRate;
        public final ForgeConfigSpec.LongValue baseGasTransferRate;

        public final ForgeConfigSpec.IntValue tickInterval;
        public final ForgeConfigSpec.DoubleValue overclockItemMultiplier;
        public final ForgeConfigSpec.DoubleValue overclockFluidMultiplier;
        public final ForgeConfigSpec.DoubleValue overclockEnergyMultiplier;
        public final ForgeConfigSpec.IntValue maxOverclocks;

        public final ForgeConfigSpec.BooleanValue enableDedicatedFileLogger;
        public final ForgeConfigSpec.ConfigValue<String> logLevel;

        public Common(ForgeConfigSpec.Builder builder) {
            builder.push("transfer_rates");
            baseItemTransferRate = builder
                    .comment("Base items transferred per tick (Default: 2,147,483,647 / 2.14 Billion max integer)")
                    .defineInRange("baseItemTransferRate", Integer.MAX_VALUE, 1, Integer.MAX_VALUE);

            baseFluidTransferRate = builder
                    .comment("Base fluid mB transferred per tick (Default: 2,147,483,647 mB / 2.14 Billion max integer)")
                    .defineInRange("baseFluidTransferRate", Integer.MAX_VALUE, 1, Integer.MAX_VALUE);

            baseEnergyTransferRate = builder
                    .comment("Base Forge Energy (FE) transferred per tick (Default: 9,223,372,036,854,775,807 FE / 9.22 Quintillion max long)")
                    .defineInRange("baseEnergyTransferRate", Long.MAX_VALUE, 1L, Long.MAX_VALUE);

            baseGasTransferRate = builder
                    .comment("Base Mekanism Gas/Chemical transferred per tick (Default: 9,223,372,036,854,775,807 max long)")
                    .defineInRange("baseGasTransferRate", Long.MAX_VALUE, 1L, Long.MAX_VALUE);
            builder.pop();

            builder.push("overclocking");
            tickInterval = builder
                    .comment("Tick interval between transfers (1 = every tick for maximum speed)")
                    .defineInRange("tickInterval", 1, 1, 100);

            overclockItemMultiplier = builder
                    .comment("Multiplier for item transfer per overclock upgrade card")
                    .defineInRange("overclockItemMultiplier", 4.0, 1.0, 100.0);

            overclockFluidMultiplier = builder
                    .comment("Multiplier for fluid transfer per overclock upgrade card")
                    .defineInRange("overclockFluidMultiplier", 4.0, 1.0, 100.0);

            overclockEnergyMultiplier = builder
                    .comment("Multiplier for energy transfer per overclock upgrade card")
                    .defineInRange("overclockEnergyMultiplier", 4.0, 1.0, 100.0);

            maxOverclocks = builder
                    .comment("Maximum number of overclock upgrades allowed per controller/node")
                    .defineInRange("maxOverclocks", 16, 1, 64);
            builder.pop();

            builder.push("logging");
            enableDedicatedFileLogger = builder
                    .comment("Enable dedicated file logging (logs/uup.log)")
                    .define("enableDedicatedFileLogger", true);

            logLevel = builder
                    .comment("Log level: DEBUG, INFO, WARN, ERROR")
                    .define("logLevel", "INFO");
            builder.pop();
        }
    }
}
