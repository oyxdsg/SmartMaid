package com.oyxdsg.smartmaid.gui;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 女仆背包菜单提供者：Shift+E 打开女仆背包时创建 {@link MaidInventoryMenu}。
 */
public class MaidInventoryMenuProvider implements MenuProvider {
    private final SmartMaidEntity maid;

    public MaidInventoryMenuProvider(SmartMaidEntity maid) {
        this.maid = maid;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("gui.smartmaid.maid_inventory");
    }

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory inv, Player player) {
        return new MaidInventoryMenu(syncId, inv, this.maid);
    }
}
