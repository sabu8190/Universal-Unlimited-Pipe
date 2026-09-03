package com.uup.item;

import com.uup.config.ModConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class OverclockUpgradeItem extends Item {

    public OverclockUpgradeItem() {
        super(new Item.Properties().stacksTo(64));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.uup.upgrade.desc"));
        double itemMult = ModConfig.COMMON != null && ModConfig.COMMON.overclockItemMultiplier != null 
                ? ModConfig.COMMON.overclockItemMultiplier.get() : 4.0;
        double fluidMult = ModConfig.COMMON != null && ModConfig.COMMON.overclockFluidMultiplier != null 
                ? ModConfig.COMMON.overclockFluidMultiplier.get() : 4.0;
        double energyMult = ModConfig.COMMON != null && ModConfig.COMMON.overclockEnergyMultiplier != null 
                ? ModConfig.COMMON.overclockEnergyMultiplier.get() : 4.0;

        tooltip.add(Component.literal(String.format("§eItem Rate: §ax%.1f", itemMult)));
        tooltip.add(Component.literal(String.format("§eFluid Rate: §ax%.1f", fluidMult)));
        tooltip.add(Component.literal(String.format("§eEnergy Rate: §ax%.1f", energyMult)));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
