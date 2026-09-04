package com.uup.item;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class NetworkCardItem extends Item {

    public NetworkCardItem() {
        super(new Item.Properties().stacksTo(16));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!level.isClientSide) {
            BlockPos targetPos = context.getClickedPos();
            if (level.getBlockState(targetPos).isAir()) {
                if (context.getPlayer() != null) {
                    context.getPlayer().sendSystemMessage(Component.literal("§c[UUP] 空気のブロックは登録できません"));
                }
                return InteractionResult.FAIL;
            }
            ItemStack stack = context.getItemInHand();
            CompoundTag tag = stack.getOrCreateTag();
            tag.putLong("TargetPos", targetPos.asLong());
            tag.putString("TargetDim", level.dimension().location().toString());
            if (context.getPlayer() != null) {
                context.getPlayer().sendSystemMessage(Component.literal("§a[UUP] Linked target block: " + targetPos.toShortString() + " in " + level.dimension().location()));
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.uup.network_card.desc"));
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains("TargetPos")) {
            BlockPos pos = BlockPos.of(tag.getLong("TargetPos"));
            tooltip.add(Component.literal("§7Linked Pos: §b" + pos.toShortString()));
        } else {
            tooltip.add(Component.literal("§cNot Linked"));
        }
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
