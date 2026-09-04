package com.uup.recipe;

import com.uup.setup.ModBlocks;
import com.uup.setup.ModItems;
import com.uup.setup.ModRecipes;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public class ControllerCraftingRecipe extends CustomRecipe {

    public ControllerCraftingRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer container, Level level) {
        if (container.getWidth() < 3 || container.getHeight() < 3) {
            return false;
        }

        for (int row = 0; row <= container.getHeight() - 3; row++) {
            for (int col = 0; col <= container.getWidth() - 3; col++) {
                if (checkPattern(container, col, row)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean checkPattern(CraftingContainer container, int startX, int startY) {
        // Pattern:
        // [Obsidian:64]    [FluidPipe:64]   [Obsidian:64]
        // [EnergyPipe:64]  [EnderPearl:64]  [ItemPipe:64]
        // [Obsidian:64]    [GasPipe:64]     [Obsidian:64]

        for (int y = 0; y < container.getHeight(); y++) {
            for (int x = 0; x < container.getWidth(); x++) {
                ItemStack stack = container.getItem(x + y * container.getWidth());
                int rx = x - startX;
                int ry = y - startY;

                if (rx >= 0 && rx < 3 && ry >= 0 && ry < 3) {
                    if (stack.getCount() < 64) {
                        return false;
                    }

                    boolean matchesExpected = switch (ry * 3 + rx) {
                        case 0 -> stack.is(Blocks.OBSIDIAN.asItem());
                        case 1 -> stack.is(ModItems.FLUID_PIPE.get());
                        case 2 -> stack.is(Blocks.OBSIDIAN.asItem());
                        case 3 -> stack.is(ModItems.ENERGY_PIPE.get());
                        case 4 -> stack.is(Items.ENDER_PEARL);
                        case 5 -> stack.is(ModItems.ITEM_PIPE.get());
                        case 6 -> stack.is(Blocks.OBSIDIAN.asItem());
                        case 7 -> stack.is(ModItems.GAS_PIPE.get());
                        case 8 -> stack.is(Blocks.OBSIDIAN.asItem());
                        default -> false;
                    };

                    if (!matchesExpected) {
                        return false;
                    }
                } else {
                    if (!stack.isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public ItemStack assemble(CraftingContainer container, RegistryAccess registryAccess) {
        return new ItemStack(ModItems.CONTROLLER.get(), 1);
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingContainer container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.getCount() >= 64) {
                // Remove 63 items from stack. The remaining 1 item will be removed by ResultSlot.onTake,
                // resulting in all 64 items being consumed per slot.
                container.removeItem(i, 63);
            }
        }
        return NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width >= 3 && height >= 3;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.CONTROLLER_RECIPE_SERIALIZER.get();
    }
}
