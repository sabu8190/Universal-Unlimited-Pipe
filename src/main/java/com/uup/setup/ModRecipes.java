package com.uup.setup;

import com.uup.UniversalUnlimitedPipe;
import com.uup.recipe.ControllerCraftingRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModRecipes {
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, UniversalUnlimitedPipe.MODID);

    public static final RegistryObject<RecipeSerializer<ControllerCraftingRecipe>> CONTROLLER_RECIPE_SERIALIZER =
            RECIPE_SERIALIZERS.register("crafting_special_controller",
                    () -> new SimpleCraftingRecipeSerializer<>(ControllerCraftingRecipe::new));

    public static void register(IEventBus bus) {
        RECIPE_SERIALIZERS.register(bus);
    }
}
