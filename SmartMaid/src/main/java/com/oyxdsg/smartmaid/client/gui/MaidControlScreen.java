package com.oyxdsg.smartmaid.client.gui;

import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.gui.MaidControlMenu;
import com.oyxdsg.smartmaid.network.MaidCommandPayload;
import com.oyxdsg.smartmaid.network.MaidSettingsPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * 女仆管理 / 设置界面（Shift+右键 打开）。
 *
 * <p>暖棕木质风：木纹面板 + 金色四角 + 木质按钮。单列，每行「设置名：当前值」，点击循环切换。
 * 所有文字用原版 {@link StringWidget} / 按钮自身承载（避免 extract 坐标问题）。</p>
 */
public class MaidControlScreen extends AbstractContainerScreen<MaidControlMenu> {
    private static final int PANEL_W = 268;
    private static final int PANEL_H = 280;

    private static final Identifier PANEL_TEX =
            Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "textures/gui/maid_panel.png");

    private record SettingRow(String field, int dataIndex, String label, String[] values, Button button) {
    }

    private final List<SettingRow> settingRows = new ArrayList<>();
    private StringWidget stateWidget;
    private StringWidget hintWidget;

    public MaidControlScreen(MaidControlMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, PANEL_W, PANEL_H);
    }

    @Override
    protected void init() {
        super.init();
        this.settingRows.clear();

        int x = this.leftPos + 12;
        int w = PANEL_W - 24;
        int y = this.topPos + 42;
        int step = 22;

        y = this.addRow("friendlyFire", MaidControlMenu.DATA_FRIENDLY_FIRE, "友军伤害",
                new String[]{"关闭", "开启"}, x, y, w, step);
        y = this.addRow("followEnabled", MaidControlMenu.DATA_FOLLOW_ENABLED, "自动跟随",
                new String[]{"关闭", "开启"}, x, y, w, step);
        y = this.addRow("followStart", MaidControlMenu.DATA_FOLLOW_START, "跟随触发距离",
                new String[]{"2格", "3格", "5格", "8格", "12格"}, x, y, w, step);
        y = this.addRow("followStop", MaidControlMenu.DATA_FOLLOW_STOP, "跟随停止距离",
                new String[]{"6格", "10格", "15格", "24格", "40格"}, x, y, w, step);
        y = this.addRow("protectMode", MaidControlMenu.DATA_PROTECT_MODE, "护主模式",
                new String[]{"主动护主", "被动护主", "关闭"}, x, y, w, step);
        y = this.addRow("maxHealth", MaidControlMenu.DATA_MAX_HEALTH, "最大生命",
                new String[]{"10", "20", "24", "40", "60", "100"}, x, y, w, step);
        y = this.addRow("hungerRate", MaidControlMenu.DATA_HUNGER_RATE, "饱食消耗速度",
                new String[]{"0.5x", "1.0x", "1.5x", "2.0x", "3.0x"}, x, y, w, step);
        y = this.addRow("regenRate", MaidControlMenu.DATA_REGEN_RATE, "回血速度",
                new String[]{"0.5x", "1.0x", "1.5x", "2.0x", "3.0x"}, x, y, w, step);
        y = this.addRow("deathDrop", MaidControlMenu.DATA_DEATH_DROP, "死亡掉落",
                new String[]{"全部掉落", "保留装备", "不掉落"}, x, y, w, step);

        // 标题 / 状态 / 提示：用 StringWidget 承载，手动居中
        this.centered(new StringWidget(this.leftPos, this.topPos + 6, 0, 12,
                Component.literal("女仆管理"), this.font));
        this.stateWidget = new StringWidget(this.leftPos, this.topPos + 24, 0, 12,
                Component.literal(""), this.font);
        this.centeredWidget(this.stateWidget);
        this.addRenderableWidget(this.stateWidget);
        this.hintWidget = new StringWidget(this.leftPos, this.topPos + PANEL_H - 16, 0, 12,
                Component.literal("点击按钮切换 · 设置与玩家绑定"), this.font);
        this.centeredWidget(this.hintWidget);
        this.addRenderableWidget(this.hintWidget);

        // 底部控制
        int btnY = this.topPos + PANEL_H - 38;
        int half = (w - 8) / 2;
        this.addRenderableWidget(new WoodButton(x, btnY, half, 20,
                Component.literal("坐下 / 站起"),
                b -> this.send(MaidCommandPayload.ACTION_TOGGLE_SIT)));
        this.addRenderableWidget(new WoodButton(x + half + 8, btnY, half, 20,
                Component.literal("召回"),
                b -> this.send(MaidCommandPayload.ACTION_RECALL)));
    }

    /** 创建并居中一个标题用 StringWidget。 */
    private void centered(StringWidget widget) {
        this.centeredWidget(widget);
        this.addRenderableWidget(widget);
    }

    private void centeredWidget(StringWidget widget) {
        int textW = this.font.width(widget.getMessage());
        widget.setWidth(textW);
        widget.setX(this.leftPos + (PANEL_W - textW) / 2);
    }

    private int addRow(String field, int dataIndex, String label, String[] values,
                       int x, int y, int w, int step) {
        final SettingRow[] holder = new SettingRow[1];
        WoodButton button = new WoodButton(x, y, w, 20, Component.literal(label), b -> this.cycle(holder[0]));
        SettingRow row = new SettingRow(field, dataIndex, label, values, button);
        holder[0] = row;
        this.settingRows.add(row);
        this.addRenderableWidget(button);
        return y + step;
    }

    private void cycle(SettingRow row) {
        int current = this.menu.getSetting(row.dataIndex());
        int next = (current + 1) % row.values().length;
        if (this.minecraft != null && this.minecraft.player != null) {
            ClientPlayNetworking.send(new MaidSettingsPayload(row.field(), next));
        }
    }

    private void send(int action) {
        if (this.minecraft != null && this.minecraft.player != null) {
            ClientPlayNetworking.send(new MaidCommandPayload(action));
        }
    }

    @Override
    public void containerTick() {
        super.containerTick();
        for (SettingRow row : this.settingRows) {
            int idx = this.menu.getSetting(row.dataIndex());
            String[] values = row.values();
            if (idx < 0 || idx >= values.length) {
                idx = 0;
            }
            row.button().setMessage(Component.literal(row.label() + "：" + values[idx]));
        }
        if (this.stateWidget != null) {
            String state = (this.menu.getSitState() == 1 ? "坐下待命" : "跟随中")
                    + "    生命 " + this.menu.getHealthInt();
            this.stateWidget.setMessage(Component.literal(state));
            this.centeredWidget(this.stateWidget);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float tickDelta) {
        super.extractBackground(extractor, mouseX, mouseY, tickDelta);
        extractor.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, PANEL_TEX,
                this.leftPos, this.topPos, 0F, 0F, PANEL_W, PANEL_H, PANEL_W, PANEL_H);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        // 文字全部由 widget 承载；这里屏蔽原版 title 与「物品栏」标签
    }
}
