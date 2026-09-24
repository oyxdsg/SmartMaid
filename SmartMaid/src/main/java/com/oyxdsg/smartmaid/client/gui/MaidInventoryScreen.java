package com.oyxdsg.smartmaid.client.gui;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.gui.MaidInventoryMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;

/**
 * 女仆背包界面：布局与渲染照搬原版玩家背包界面（InventoryScreen）——
 * 用标准容器背景 {@link AbstractContainerScreen#INVENTORY_LOCATION}，槽位位置由 MaidInventoryMenu 照搬原版坐标。
 * 左上角渲染女仆自身缩小模型（跟随鼠标旋转，与原版玩家背包一致）。
 */
public class MaidInventoryScreen extends AbstractContainerScreen<MaidInventoryMenu> {
    private float xMouse;
    private float yMouse;

    public MaidInventoryScreen(MaidInventoryMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        // 标题/背包标签位置（照搬原版布局）
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = this.imageHeight - 92;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickDelta) {
        super.extractRenderState(extractor, mouseX, mouseY, tickDelta);
        this.xMouse = mouseX;
        this.yMouse = mouseY;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickDelta) {
        super.extractBackground(extractor, mouseX, mouseY, tickDelta);
        // 画标准容器背景（照搬原版 InventoryScreen.extractBackground）
        extractor.blit(RenderPipelines.GUI_TEXTURED, AbstractContainerScreen.INVENTORY_LOCATION,
                this.leftPos, this.topPos, 0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, 256);

        // 左上角渲染女仆缩小模型（照搬原版玩家背包的位置与跟随鼠标旋转）
        Entity maid = this.minecraft.level != null
                ? this.minecraft.level.getEntity(this.menu.getMaidId())
                : null;
        if (maid instanceof SmartMaidEntity) {
            InventoryScreen.extractEntityInInventoryFollowsMouse(extractor,
                    this.leftPos + 26, this.topPos + 8,
                    this.leftPos + 75, this.topPos + 78,
                    30, 0.0625F, this.xMouse, this.yMouse, (SmartMaidEntity) maid);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        extractor.text(this.font, this.title, this.leftPos + this.titleLabelX, this.topPos + this.titleLabelY, 0x404040);
    }
}
