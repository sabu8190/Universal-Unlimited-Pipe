package com.uup.recipe;

import com.uup.setup.ModItems;
import com.uup.setup.ModRecipes;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public class ControllerCraftingRecipe implements CraftingRecipe {

    private final ResourceLocation id;
    private final CraftingBookCategory category;

    public ControllerCraftingRecipe(ResourceLocation id, CraftingBookCategory category) {
        this.id = id;
        this.category = category;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public CraftingBookCategory category() {
        return category != null ? category : CraftingBookCategory.MISC;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.CONTROLLER_RECIPE_SERIALIZER.get();
    }

    @Override
    public boolean isSpecial() {
        return false;
    }

    @Override
    public boolean showNotification() {
        return true;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess registryAccess) {
        return new ItemStack(ModItems.CONTROLLER.get(), 1);
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> list = NonNullList.create();
        list.add(Ingredient.of(Blocks.OBSIDIAN));
        list.add(Ingredient.of(ModItems.FLUID_PIPE.get()));
        list.add(Ingredient.of(Blocks.OBSIDIAN));
        list.add(Ingredient.of(ModItems.ENERGY_PIPE.get()));
        list.add(Ingredient.of(Items.ENDER_PEARL));
        list.add(Ingredient.of(ModItems.ITEM_PIPE.get()));
        list.add(Ingredient.of(Blocks.OBSIDIAN));
        list.add(Ingredient.of(ModItems.GAS_PIPE.get()));
        list.add(Ingredient.of(Blocks.OBSIDIAN));
        return list;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width >= 3 && height >= 3;
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
                    int slotIndex = ry * 3 + rx;
                    boolean matchesExpected = switch (slotIndex) {
                        case 0, 2, 6, 8 -> stack.is(Blocks.OBSIDIAN.asItem());
                        case 1 -> stack.is(ModItems.FLUID_PIPE.get());
                        case 3 -> stack.is(ModItems.ENERGY_PIPE.get());
                        case 4 -> stack.is(Items.ENDER_PEARL);
                        case 5 -> stack.is(ModItems.ITEM_PIPE.get());
                        case 7 -> stack.is(ModItems.GAS_PIPE.get());
                        default -> false;
                    };

                    if (!matchesExpected) {
                        return false;
                    }

                    int required = Math.min(64, stack.getMaxStackSize());
                    if (stack.getCount() < required) {
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
            if (!stack.isEmpty()) {
                int required = Math.min(64, stack.getMaxStackSize());
                if (stack.getCount() >= required && required > 1) {
                    container.removeItem(i, required - 1);
                }
            }
        }
        return NonNullList.withSize(container.getContainerSize(), ItemStack.EMPTY);
    }
}
