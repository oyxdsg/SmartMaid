package com.oyxdsg.smartmaid.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oyxdsg.smartmaid.network.MaidMenuActionPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 二级「任务」页（N1 步骤 4）：长期/短期两个队列的前端。
 *
 * <p>显示当前执行项 + 两队列，行内提供 停止这一项 / 移除 / 上移 / 停止（长期）；底部 清空短期 / 添加任务。</p>
 */
public class MaidTaskScreen extends MaidSubScreen {

    private JsonObject state;
    private int queryTimer;
    private String toast = "";
    private long toastUntil;

    /** 每个可点元素：rect + 动作。 */
    private record Hot(int x, int y, int w, int h, Runnable action) {
    }

    private final List<Hot> hots = new ArrayList<>();

    /** 当前活跃的任务页（供 S2C 状态刷新定位）。 */
    public static MaidTaskScreen ACTIVE;

    public MaidTaskScreen(Screen parent, JsonObject initialState) {
        super(parent, Component.literal("任务"));
        this.state = initialState;
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

    public void updateState(String json) {
        try {
            this.state = JsonParser.parseString(json).getAsJsonObject();
            if (this.state.has("receipt_msg")) {
                boolean ok = !this.state.has("receipt_ok") || this.state.get("receipt_ok").getAsBoolean();
                this.toast = (ok ? "" : "[!] ") + this.state.get("receipt_msg").getAsString();
                this.toastUntil = System.currentTimeMillis() + 1500;
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (++this.queryTimer >= 20) {
            this.queryTimer = 0;
            MaidNet.send(MaidMenuActionPayload.of("query"));
        }
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor ex, int mouseX, int mouseY, float tickDelta) {
        this.hots.clear();
        JsonObject cur = (state != null && state.has("current") && state.get("current").isJsonObject())
                ? state.getAsJsonObject("current") : null;

        // 正在执行（固定区）
        if (cur != null) {
            drawSectionTitle(ex, "正在执行", this.top + 34);
            drawRow(ex, this.top + 46, cur, false, true);
        }

        // 长期队列（固定区，预留 2 行）
        int yLong = this.top + 76;
        drawSectionTitle(ex, "长期队列 · 短期清空后才执行", yLong);
        JsonArray longs = arrayOf("long");
        if (longs.isEmpty()) {
            ex.text(this.font, Component.literal("（空）"), this.left + 22, yLong + 13, MaidTheme.TEXT_3);
        } else {
            for (int i = 0; i < Math.min(longs.size(), 2); i++) {
                drawRow(ex, yLong + 12 + i * ROW_H, longs.get(i).getAsJsonObject(), true, false);
            }
        }

        // 短期队列（固定区，预留 3 行）
        int yShort = this.top + 130;
        drawSectionTitle(ex, "短期队列 · 优先执行", yShort);
        JsonArray shorts = arrayOf("short");
        if (shorts.isEmpty()) {
            ex.text(this.font, Component.literal("（空）"), this.left + 22, yShort + 13, MaidTheme.TEXT_3);
        } else {
            renderGroupedShort(ex, shorts, yShort + 12);
        }

        // 底部按钮
        int by = this.top + PANEL_H - 26;
        int bw = 84;
        button(ex, this.left + 16, by, bw, "清空短期", () ->
                MaidNet.send(MaidMenuActionPayload.op("clear", "", "SHORT")));
        button(ex, this.left + 16 + bw + 8, by, bw, "清空长期", () ->
                MaidNet.send(MaidMenuActionPayload.op("clear", "", "LONG")));
        button(ex, this.left + PANEL_W - 16 - bw, by, bw, "＋ 添加任务", () ->
                this.minecraft.setScreenAndShow(new MaidAddTaskScreen(this, this.state)));

        if (!this.toast.isEmpty() && System.currentTimeMillis() < this.toastUntil) {
            ex.centeredText(this.font, Component.literal(this.toast),
                    this.left + PANEL_W / 2, this.top + PANEL_H - 42, MaidTheme.TEXT_2);
        }
    }

    private static final int ROW_H = 18;

    private void drawSectionTitle(GuiGraphicsExtractor ex, String s, int y) {
        ex.text(this.font, Component.literal(s), this.left + 16, y, MaidTheme.TEXT_2);
    }

    private void drawRow(GuiGraphicsExtractor ex, int y, JsonObject item, boolean isLong, boolean isCurrent) {
        String id = str(item, "id");
        String name = str(item, "name");
        String st = str(item, "state");
        int interrupts = item.has("interrupts") ? item.get("interrupts").getAsInt() : 0;
        MaidTheme.roundRect(ex, this.left + 16, y, this.left + PANEL_W - 16, y + ROW_H - 2, MaidTheme.CARD, 0);
        String label = name.isEmpty() ? id : name;
        String status = "RUNNING".equals(st) ? "进行中"
                : "PAUSED".equals(st) ? ("暂停中" + (interrupts > 0 ? "·打断" + interrupts : "")) : "排队中";
        ex.text(this.font, Component.literal(label + "  ·  " + status), this.left + 22, y + 4, MaidTheme.TEXT);

        int bx = this.left + PANEL_W - 16 - 4;
        if (isCurrent) {
            bx -= 56;
            button(ex, bx, y, 52, "停止这一项", () ->
                    MaidNet.send(MaidMenuActionPayload.of("cancelCurrent")));
        } else if (isLong) {
            bx -= 34;
            button(ex, bx, y, 30, "停止", () ->
                    MaidNet.send(MaidMenuActionPayload.op("stop", id, "LONG")));
            bx -= 34;
            button(ex, bx, y, 30, "设当前", () ->
                    MaidNet.send(MaidMenuActionPayload.op("promote", id, "LONG")));
            bx -= 26;
            button(ex, bx, y, 22, "↑", () ->
                    MaidNet.send(MaidMenuActionPayload.op("moveUp", id, "LONG")));
        } else {
            bx -= 26;
            button(ex, bx, y, 22, "×", () ->
                    MaidNet.send(MaidMenuActionPayload.op("remove", id, "SHORT")));
            bx -= 26;
            button(ex, bx, y, 22, "↑", () ->
                    MaidNet.send(MaidMenuActionPayload.op("moveUp", id, "SHORT")));
        }
    }

    /** 短期队列：同 group 折叠成一行（「烧所有矿物」N 项）+ 整组停止（§8.5）。 */
    private void renderGroupedShort(GuiGraphicsExtractor ex, JsonArray shorts, int y) {
        java.util.Set<String> doneGroups = new java.util.HashSet<>();
        int rows = 0;
        for (int i = 0; i < shorts.size() && rows < 3; i++) {
            JsonObject item = shorts.get(i).getAsJsonObject();
            String g = str(item, "group");
            if (!g.isEmpty()) {
                if (doneGroups.contains(g)) {
                    continue;
                }
                doneGroups.add(g);
                int n = 0;
                for (int j = 0; j < shorts.size(); j++) {
                    if (g.equals(str(shorts.get(j).getAsJsonObject(), "group"))) {
                        n++;
                    }
                }
                String label = str(item, "groupLabel");
                if (label.isEmpty()) {
                    label = g;
                }
                drawGroupRow(ex, y, "「" + label + "」 " + n + " 项", g);
            } else {
                drawRow(ex, y, item, false, false);
            }
            y += ROW_H;
            rows++;
        }
    }

    private void drawGroupRow(GuiGraphicsExtractor ex, int y, String label, String groupId) {
        MaidTheme.roundRect(ex, this.left + 16, y, this.left + PANEL_W - 16, y + ROW_H - 2, MaidTheme.CARD, 0);
        ex.text(this.font, Component.literal(label), this.left + 22, y + 4, MaidTheme.TEXT);
        int bx = this.left + PANEL_W - 16 - 4 - 60;
        button(ex, bx, y, 56, "整组停止", () ->
                MaidNet.send(MaidMenuActionPayload.op("stopGroup", groupId, "")));
    }

    private void button(GuiGraphicsExtractor ex, int x, int y, int w, String label, Runnable action) {
        MaidTheme.roundRect(ex, x, y, x + w, y + ROW_H - 2, MaidTheme.BTN, 0);
        ex.centeredText(this.font, Component.literal(label), x + w / 2, y + 4, MaidTheme.CARD);
        this.hots.add(new Hot(x, y, w, ROW_H - 2, action));
    }

    private JsonArray arrayOf(String key) {
        if (state == null || !state.has(key) || !state.get(key).isJsonArray()) {
            return new JsonArray();
        }
        return state.getAsJsonArray(key);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean focused) {
        if (com.oyxdsg.smartmaid.compat.MaidCompat.isPrimaryMouseButton(event.button())) {
            for (Hot h : this.hots) {
                if (inside(event.x(), event.y(), h.x(), h.y(), h.w(), h.h())) {
                    h.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, focused);
    }
}
