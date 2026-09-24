package com.oyxdsg.smartmaid.init;

import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.gui.MaidControlMenu;
import com.oyxdsg.smartmaid.gui.MaidInventoryMenu;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

/**
 * 女仆 GUI 菜单注册：背包（Shift+E）+ 管理（Shift+右键）。
 */
public final class ModMenus {
    /** 女仆背包（Shift+E 打开） */
    public static MenuType<MaidInventoryMenu> MAID_INVENTORY_MENU;
    /** 女仆管理（Shift+右键 打开：坐下/站起、召回、清空目标） */
    public static MenuType<MaidControlMenu> MAID_CONTROL_MENU;

    private ModMenus() {
    }

    public static void register() {
        MAID_INVENTORY_MENU = Registry.register(BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "maid_inventory")),
                new MenuType<>(MaidInventoryMenu::new, FeatureFlags.VANILLA_SET));
        MAID_CONTROL_MENU = Registry.register(BuiltInRegistries.MENU,
                ResourceKey.create(Registries.MENU, Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "maid_control")),
                new MenuType<>(MaidControlMenu::new, FeatureFlags.VANILLA_SET));
    }
}
