package com.oyxdsg.smartmaid.gui;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 女仆管理菜单提供者：Shift+右键 打开时创建 {@link MaidControlMenu}。
 */
public class MaidControlMenuProvider implements MenuProvider {
    private final SmartMaidEntity maid;

    public MaidControlMenuProvider(SmartMaidEntity maid) {
        this.maid = maid;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("gui.smartmaid.maid_control");
    }

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory inv, Player player) {
        return new MaidControlMenu(syncId, inv, this.maid);
    }
}
