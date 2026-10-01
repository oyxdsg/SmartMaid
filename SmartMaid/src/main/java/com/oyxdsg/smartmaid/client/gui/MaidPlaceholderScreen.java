package com.oyxdsg.smartmaid.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** N1 二级占位页（对话 / 设置 / 动作）：只保证能打开、能返回，不崩。 */
public class MaidPlaceholderScreen extends MaidSubScreen {

    private final String label;

    public MaidPlaceholderScreen(Screen parent, String label) {
        super(parent, Component.literal(label));
        this.label = label;
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor ex, int mouseX, int mouseY, float tickDelta) {
        int cx = this.left + PANEL_W / 2;
        ex.centeredText(this.font, Component.literal(label), cx, this.top + PANEL_H / 2 - 12, MaidTheme.TEXT);
        ex.centeredText(this.font, Component.literal("该页在 N1 为占位，后续版本实现"), cx, this.top + PANEL_H / 2 + 4, MaidTheme.TEXT_3);
    }
}
