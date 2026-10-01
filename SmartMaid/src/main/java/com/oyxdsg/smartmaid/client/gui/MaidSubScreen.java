package com.oyxdsg.smartmaid.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 二级页基类（N1）：统一「世界遮罩 + 面板 + 标题栏 + 返回」链路。
 * 子类只实现 {@link #renderContent}。
 */
public abstract class MaidSubScreen extends Screen {

    protected static final int PANEL_W = 340;
    protected static final int PANEL_H = 210;
    protected static final int BACK_W = 44;
    protected static final int BACK_H = 18;

    protected final Screen parent;
    protected int left;
    protected int top;
    private int backX, backY;
    private boolean hoverBack;

    protected MaidSubScreen(Screen parent, Component title) {
        super(title);
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.left = (this.width - PANEL_W) / 2;
        this.top = (this.height - PANEL_H) / 2;
        this.backX = this.left + 12;
        this.backY = this.top + 8;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor ex, int mouseX, int mouseY, float tickDelta) {
        if (this.minecraft != null && this.minecraft.level != null) {
            this.extractBlurredBackground(ex);
        }
        ex.fill(0, 0, this.width, this.height, MaidTheme.WORLD_OVERLAY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ex, int mouseX, int mouseY, float tickDelta) {
        MaidTheme.roundRect(ex, this.left, this.top, this.left + PANEL_W, this.top + PANEL_H, MaidTheme.PANEL, 8);
        // 返回按钮
        MaidTheme.roundRect(ex, backX, backY, backX + BACK_W, backY + BACK_H,
                hoverBack ? MaidTheme.HOVER_STRONG : MaidTheme.CARD, 6);
        ex.centeredText(this.font, Component.literal("返回"), backX + BACK_W / 2, backY + 5, MaidTheme.TEXT_2);
        // 标题居中
        ex.centeredText(this.font, this.title, this.left + PANEL_W / 2, this.top + 10, MaidTheme.TEXT);
        // 分割线
        int lineY = this.top + 30;
        ex.fill(this.left + 12, lineY, this.left + PANEL_W - 12, lineY + 1, MaidTheme.BORDER);
        renderContent(ex, mouseX, mouseY, tickDelta);
    }

    /** 子类在此绘制内容区（y 从 {@code top+36} 起）。 */
    protected abstract void renderContent(GuiGraphicsExtractor ex, int mouseX, int mouseY, float tickDelta);

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean focused) {
        if (event.button() == 0 && inside(event.x(), event.y(), backX, backY, BACK_W, BACK_H)) {
            goBack();
            return true;
        }
        return super.mouseClicked(event, focused);
    }

    @Override
    public void mouseMoved(double mx, double my) {
        this.hoverBack = inside(mx, my, backX, backY, BACK_W, BACK_H);
        super.mouseMoved(mx, my);
    }

    protected void goBack() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }

    @Override
    public void onClose() {
        goBack();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    protected static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
