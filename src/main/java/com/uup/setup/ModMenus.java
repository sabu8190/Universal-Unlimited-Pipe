package com.uup.setup;

import com.uup.UniversalUnlimitedPipe;
import com.uup.gui.NodeMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, UniversalUnlimitedPipe.MODID);

    public static final RegistryObject<MenuType<NodeMenu>> NODE_MENU =
            MENUS.register("node_menu", () -> IForgeMenuType.create(NodeMenu::new));

    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}
