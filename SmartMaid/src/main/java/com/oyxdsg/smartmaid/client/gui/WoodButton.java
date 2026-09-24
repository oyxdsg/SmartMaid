package com.oyxdsg.smartmaid.client.gui;

import com.oyxdsg.smartmaid.SmartMaid;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 木质按钮：固定尺寸木纹贴图（宽/窄两种），文字走原版按钮文字通道、白色居中。
 */
public class WoodButton extends Button {

    private static final Identifier TEX = id("maid_button.png");
    private static final Identifier TEX_HOVER = id("maid_button_hover.png");
    private static final Identifier TEX_S = id("maid_button_s.png");
    private static final Identifier TEX_S_HOVER = id("maid_button_s_hover.png");

    /** 宽按钮贴图宽度（设置项）；小于该宽度用窄按钮贴图（底部分栏按钮） */
    private static final int WIDE_W = 244;
    private static final int SMALL_W = 118;
    private static final int BTN_H = 20;

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "textures/gui/" + name);
    }

    public WoodButton(int x, int y, int w, int h, Component message, OnPress onPress) {
        super(x, y, w, h, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        boolean small = this.getWidth() < 200;
        boolean hover = this.isHoveredOrFocused();
        Identifier tex = small
                ? (hover ? TEX_S_HOVER : TEX_S)
                : (hover ? TEX_HOVER : TEX);
        int texW = small ? SMALL_W : WIDE_W;
        g.blit(RenderPipelines.GUI_TEXTURED, tex, this.getX(), this.getY(),
                0F, 0F, texW, BTN_H, texW, BTN_H);
        // 走原版按钮文字通道（ActiveTextCollector），否则文字不会被绘制
        this.extractDefaultLabel(g.textRendererForWidget(
                this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
    }
}
