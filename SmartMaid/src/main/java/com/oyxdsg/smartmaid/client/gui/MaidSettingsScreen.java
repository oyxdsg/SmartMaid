package com.oyxdsg.smartmaid.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oyxdsg.smartmaid.network.MaidSettingsPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 二级「设置」页（N1）：把原来 9 项玩家绑定设置搬进新菜单。
 * 点击整行循环切换档位；客户端只发「字段 + 档位索引」给服务端权威修改，回推状态刷新。
 */
public class MaidSettingsScreen extends MaidSubScreen {

    /** 当前活跃设置页（供 S2C 状态刷新定位）。 */
    public static MaidSettingsScreen ACTIVE;

    private record Row(String field, String label, String[] values) {
    }

    private static final List<Row> ROWS = List.of(
            new Row("friendlyFire", "友军伤害", new String[]{"关闭", "开启"}),
            new Row("followEnabled", "自动跟随", new String[]{"关闭", "开启"}),
            new Row("followStart", "跟随触发距离", new String[]{"2格", "3格", "5格", "8格", "12格"}),
            new Row("followStop", "跟随停止距离", new String[]{"6格", "10格", "15格", "24格", "40格"}),
            new Row("maxHealth", "最大生命", new String[]{"10", "20", "24", "40", "60", "100"}),
            new Row("hungerRate", "饱食消耗速度", new String[]{"0.5x", "1.0x", "1.5x", "2.0x", "3.0x"}),
            new Row("regenRate", "回血速度", new String[]{"0.5x", "1.0x", "1.5x", "2.0x", "3.0x"}),
            new Row("deathDrop", "死亡掉落", new String[]{"全部掉落", "保留装备", "不掉落"}));

    private final List<int[]> rects = new ArrayList<>();
    private JsonObject state;
    private int hover = -1;

    public MaidSettingsScreen(Screen parent, JsonObject state) {
        super(parent, Component.literal("设置"));
        this.state = state;
    }

    public void updateState(String json) {
        try {
            this.state = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void init() {
        super.init();
        ACTIVE = this;
    }

    @Override
    public void removed() {
        super.removed();
        if (ACTIVE == this) {
            ACTIVE = null;
        }
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor ex, int mouseX, int mouseY, float tickDelta) {
        this.rects.clear();
        int y = this.top + 36;
        int rowW = PANEL_W - 32;
        this.hover = -1;
        for (int i = 0; i < ROWS.size(); i++) {
            Row row = ROWS.get(i);
            int rx = this.left + 16;
            boolean hov = mouseX >= rx && mouseX < rx + rowW && mouseY >= y && mouseY < y + 17;
            MaidTheme.roundRect(ex, rx, y, rx + rowW, y + 17, hov ? MaidTheme.HOVER : MaidTheme.CARD, 0);
            ex.text(this.font, Component.literal(row.label()), rx + 8, y + 5, MaidTheme.TEXT);
            String val = row.values()[idx(row)];
            ex.text(this.font, Component.literal(val), rx + rowW - 8 - this.font.width(val), y + 5, MaidTheme.ACCENT);
            this.rects.add(new int[]{rx, y, rowW, 17});
            y += 18;
        }
        ex.text(this.font, Component.literal("点击一行循环切换 · 设置与玩家绑定"),
                this.left + 16, this.top + PANEL_H - 16, MaidTheme.TEXT_3);
    }

    private int idx(Row row) {
        if (state != null && state.has("settings")
                && state.getAsJsonObject("settings").has(row.field())) {
            int v = state.getAsJsonObject("settings").get(row.field()).getAsInt();
            return Math.max(0, Math.min(v, row.values().length - 1));
        }
        return 0;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        if (event.button() == 0) {
            for (int i = 0; i < this.rects.size(); i++) {
                int[] r = this.rects.get(i);
                if (inside(event.x(), event.y(), r[0], r[1], r[2], r[3])) {
                    Row row = ROWS.get(i);
                    int next = (idx(row) + 1) % row.values().length;
                    ClientPlayNetworking.send(new MaidSettingsPayload(row.field(), next));
                    return true;
                }
            }
        }
        return super.mouseClicked(event, focused);
    }
}
