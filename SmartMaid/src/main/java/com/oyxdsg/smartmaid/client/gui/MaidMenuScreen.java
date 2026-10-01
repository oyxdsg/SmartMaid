package com.oyxdsg.smartmaid.client.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.network.MaidCommandPayload;
import com.oyxdsg.smartmaid.network.MaidMenuActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

/**
 * 女仆主菜单（N1，Shift+右键）：纯 {@link Screen}，WBS 绿 + 遮罩分层。
 *
 * <p>一级：3D 预览（左）｜ 5 入口（右）｜ 状态条（生命爱心 + 饱食 + 坐姿）｜ 4 高频动作。
 * 背包入口只做跳转（发既有 {@code ACTION_OPEN_INVENTORY}）；其余入口进二级占位页。</p>
 */
public class MaidMenuScreen extends Screen {

    private static final int PANEL_W = 360;
    private static final int PANEL_H = 252;

    private static final net.minecraft.world.entity.EquipmentSlot[] EQUIP = {
            net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.CHEST,
            net.minecraft.world.entity.EquipmentSlot.LEGS,
            net.minecraft.world.entity.EquipmentSlot.FEET,
            net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            net.minecraft.world.entity.EquipmentSlot.OFFHAND};

    private static final String[] ENTRY_KEYS = {"任务", "背包", "对话", "设置", "动作"};
    private static final String[] ENTRY_SUBS = {"队列管理", "41 格 · 装备", "与女仆聊天", "跟随 · 护主", "表情动作"};
    private static final String[] ACTION_KEYS = {"跟随我", "坐下待命", "召回", "停止当前"};

    private static final Identifier HEART_BG = Identifier.withDefaultNamespace("hud/heart/container");
    private static final Identifier HEART_FULL = Identifier.withDefaultNamespace("hud/heart/full");
    private static final Identifier HEART_HALF = Identifier.withDefaultNamespace("hud/heart/half");
    private static final Identifier FOOD_BG = Identifier.withDefaultNamespace("hud/food_empty");
    private static final Identifier FOOD_FULL = Identifier.withDefaultNamespace("hud/food_full");
    private static final Identifier FOOD_HALF = Identifier.withDefaultNamespace("hud/food_half");

    private final int maidId;
    private int foodLive;
    private JsonObject state;
    private int queryTimer;
    private int left;
    private int top;

    private final int[] entryX = new int[5];
    private final int[] entryY = new int[5];
    private final int[] entryW = new int[5];
    private final int[] entryH = new int[5];
    private final int[] actX = new int[4];
    private final int[] actY = new int[4];
    private final int[] actW = new int[4];
    private final int[] actH = new int[4];

    private int hoverEntry = -1;
    private int hoverAction = -1;
    private String toast = "";
    private long toastUntil;

    public MaidMenuScreen(int maidId, int food) {
        super(Component.literal("女仆菜单"));
        this.maidId = maidId;
        this.foodLive = food;
    }

    /** 收到服务端状态快照（S2C）：更新饱食度与队列摘要。 */
    public void updateState(String json) {
        try {
            this.state = JsonParser.parseString(json).getAsJsonObject();
            if (this.state.has("food")) {
                this.foodLive = this.state.get("food").getAsInt();
            }
            if (this.state.has("receipt_msg")) {
                boolean ok = !this.state.has("receipt_ok") || this.state.get("receipt_ok").getAsBoolean();
                showToast((ok ? "" : "[!] ") + this.state.get("receipt_msg").getAsString());
            }
        } catch (Exception ignored) {
            // 解析失败保持原状态
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (++this.queryTimer >= 20) {
            this.queryTimer = 0;
            ClientPlayNetworking.send(MaidMenuActionPayload.of("query"));
        }
    }

    /** 当前活跃的主菜单实例（供 S2C 状态刷新定位；26.2 无可访问的 Minecraft.screen）。 */
    public static MaidMenuScreen ACTIVE;

    @Override
    protected void init() {
        ACTIVE = this;
        this.left = (this.width - PANEL_W) / 2;
        this.top = (this.height - PANEL_H) / 2;
        int ex = this.left + 162;
        int ey = this.top + 36;
        int ew = PANEL_W - 162 - 16;
        int eh = 22;
        for (int i = 0; i < 5; i++) {
            entryX[i] = ex;
            entryY[i] = ey + i * (eh + 4);
            entryW[i] = ew;
            entryH[i] = eh;
        }
        int gap = 6;
        int aw = (PANEL_W - 32 - 3 * gap) / 4;
        int ay = this.top + PANEL_H - 30;
        for (int i = 0; i < 4; i++) {
            actX[i] = this.left + 16 + i * (aw + gap);
            actY[i] = ay;
            actW[i] = aw;
            actH[i] = 20;
        }
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
        MaidTheme.roundRect(ex, this.left, this.top, this.left + PANEL_W, this.top + PANEL_H, MaidTheme.PANEL, 0);
        ex.centeredText(this.font, Component.literal("女仆菜单"), this.left + PANEL_W / 2, this.top + 10, MaidTheme.TEXT);
        ex.fill(this.left + 12, this.top + 26, this.left + PANEL_W - 12, this.top + 27, MaidTheme.BORDER);

        SmartMaidEntity maid = getMaid();

        // 左：3D 预览（放大框 + 缩小模型，避免截断）
        int px1 = this.left + 16;
        int py1 = this.top + 34;
        int px2 = this.left + 150;
        int py2 = this.top + 156;
        MaidTheme.roundRect(ex, px1, py1, px2, py2, MaidTheme.CARD, 0);
        if (maid != null) {
            InventoryScreen.extractEntityInInventoryFollowsMouse(ex, px1, py1, px2, py2,
                    46, 0.0625F, mouseX, mouseY, maid);
            ex.centeredText(this.font, Component.literal(maid.getName().getString()),
                    (px1 + px2) / 2, py2 + 2, MaidTheme.TEXT_2);
            drawEquipment(ex, maid);
        } else {
            ex.centeredText(this.font, Component.literal("（女仆未加载）"),
                    (px1 + px2) / 2, (py1 + py2) / 2, MaidTheme.TEXT_3);
        }

        // 右：5 入口
        for (int i = 0; i < 5; i++) {
            MaidTheme.roundRect(ex, entryX[i], entryY[i], entryX[i] + entryW[i], entryY[i] + entryH[i],
                    hoverEntry == i ? MaidTheme.HOVER : MaidTheme.CARD, 0);
            ex.text(this.font, Component.literal(ENTRY_KEYS[i]), entryX[i] + 12, entryY[i] + 7, MaidTheme.TEXT);
            ex.text(this.font, Component.literal(entrySub(i)), entryX[i] + 64, entryY[i] + 7, MaidTheme.TEXT_3);
        }

        // 状态条：生命爱心 + 饱食 + 坐姿
        int sy = this.top + PANEL_H - 58;
        MaidTheme.roundRect(ex, this.left + 16, sy, this.left + PANEL_W - 16, sy + 20, MaidTheme.TRACK, 0);
        int hp = maid == null ? 0 : (int) Math.ceil(maid.getHealth());
        int maxHp = maid == null ? 20 : Math.max(1, (int) Math.ceil(maid.getMaxHealth()));
        int hpHalf = (int) Math.round(20.0 * hp / maxHp); // 20 个半心单位 = 10 心
        for (int i = 0; i < 10; i++) {
            drawHeart(ex, this.left + 24 + i * 8, sy + 6, hpHalf - i * 2);
        }
        int foodHalf = Math.max(0, Math.min(20, foodLive));
        for (int i = 0; i < 10; i++) {
            drawFood(ex, this.left + 130 + i * 8, sy + 6, foodHalf - i * 2);
        }
        String state = maid == null ? "未加载" : (maid.isOrderedToSit() ? "坐下待命" : "跟随中");
        ex.text(this.font, Component.literal(state),
                this.left + PANEL_W - 16 - this.font.width(state) - 8, sy + 7, MaidTheme.TEXT_2);

        // 4 高频动作
        for (int i = 0; i < 4; i++) {
            MaidTheme.roundRect(ex, actX[i], actY[i], actX[i] + actW[i], actY[i] + actH[i],
                    hoverAction == i ? MaidTheme.BTN_HOVER : MaidTheme.BTN, 0);
            ex.centeredText(this.font, Component.literal(ACTION_KEYS[i]),
                    actX[i] + actW[i] / 2, actY[i] + 6, MaidTheme.CARD);
        }

        if (!toast.isEmpty() && System.currentTimeMillis() < toastUntil) {
            ex.centeredText(this.font, Component.literal(toast), this.left + PANEL_W / 2, this.top + PANEL_H + 6, MaidTheme.TEXT);
        }
    }

    /** 爱心：v>=2 满、v==1 半、否则空心。复用原版 HUD 精灵。 */
    private void drawHeart(GuiGraphicsExtractor ex, int x, int y, int v) {
        ex.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_BG, x, y, 9, 9);
        if (v >= 2) {
            ex.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_FULL, x, y, 9, 9);
        } else if (v == 1) {
            ex.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_HALF, x, y, 9, 9);
        }
    }

    /** 饱食度：复用原版 HUD 精灵（food_empty/full/half）。 */
    private void drawFood(GuiGraphicsExtractor ex, int x, int y, int v) {
        ex.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_BG, x, y, 9, 9);
        if (v >= 2) {
            ex.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_FULL, x, y, 9, 9);
        } else if (v == 1) {
            ex.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_HALF, x, y, 9, 9);
        }
    }

    private String entrySub(int i) {
        if (i == 0 && this.state != null) {
            int n = (this.state.has("short_size") ? this.state.get("short_size").getAsInt() : 0)
                    + (this.state.has("long_size") ? this.state.get("long_size").getAsInt() : 0);
            return "队列 " + n + " 项";
        }
        return ENTRY_SUBS[i];
    }

    private SmartMaidEntity getMaid() {
        if (this.minecraft == null || this.minecraft.level == null) {
            return null;
        }
        Entity e = this.minecraft.level.getEntity(this.maidId);
        return e instanceof SmartMaidEntity m ? m : null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean focused) {
        if (event.button() != 0) {
            return super.mouseClicked(event, focused);
        }
        double mx = event.x();
        double my = event.y();
        for (int i = 0; i < 5; i++) {
            if (inside(mx, my, entryX[i], entryY[i], entryW[i], entryH[i])) {
                onEntry(i);
                return true;
            }
        }
        for (int i = 0; i < 4; i++) {
            if (inside(mx, my, actX[i], actY[i], actW[i], actH[i])) {
                onAction(i);
                return true;
            }
        }
        return super.mouseClicked(event, focused);
    }

    @Override
    public void mouseMoved(double mx, double my) {
        hoverEntry = -1;
        hoverAction = -1;
        for (int i = 0; i < 5; i++) {
            if (inside(mx, my, entryX[i], entryY[i], entryW[i], entryH[i])) {
                hoverEntry = i;
            }
        }
        for (int i = 0; i < 4; i++) {
            if (inside(mx, my, actX[i], actY[i], actW[i], actH[i])) {
                hoverAction = i;
            }
        }
        super.mouseMoved(mx, my);
    }

    /** 装备概览：4 盔甲 + 主手 + 副手（客户端直接读实体，零同步）。 */
    private void drawEquipment(GuiGraphicsExtractor ex, SmartMaidEntity maid) {
        int cell = 20;
        int total = EQUIP.length * cell;
        int x0 = this.left + (PANEL_W - total) / 2;
        int y = this.top + 168;
        for (int i = 0; i < EQUIP.length; i++) {
            int x = x0 + i * cell;
            MaidTheme.roundRect(ex, x, y, x + cell - 2, y + cell - 2, MaidTheme.CARD, 0);
            net.minecraft.world.item.ItemStack st = maid.getItemBySlot(EQUIP[i]);
            if (!st.isEmpty()) {
                ex.item(st, x + 1, y + 1);
            }
        }
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void onEntry(int i) {
        switch (i) {
            case 0 -> this.minecraft.setScreenAndShow(new MaidTaskScreen(this, this.state));
            case 1 -> {
                ClientPlayNetworking.send(new MaidCommandPayload(MaidCommandPayload.ACTION_OPEN_INVENTORY));
                this.onClose();
            }
            case 2 -> this.minecraft.setScreenAndShow(new MaidPlaceholderScreen(this, "对话"));
            case 3 -> this.minecraft.setScreenAndShow(new MaidSettingsScreen(this, this.state));
            case 4 -> this.minecraft.setScreenAndShow(new MaidPlaceholderScreen(this, "动作"));
            default -> {
            }
        }
    }

    private void onAction(int i) {
        SmartMaidEntity maid = getMaid();
        switch (i) {
            case 0 -> {
                if (maid != null && maid.isOrderedToSit()) {
                    sendCmd(MaidCommandPayload.ACTION_TOGGLE_SIT);
                }
                showToast("跟随中");
            }
            case 1 -> {
                if (maid == null || !maid.isOrderedToSit()) {
                    sendCmd(MaidCommandPayload.ACTION_TOGGLE_SIT);
                }
                showToast("坐下待命");
            }
            case 2 -> {
                sendCmd(MaidCommandPayload.ACTION_RECALL);
                showToast("已召回");
            }
            case 3 -> {
                ClientPlayNetworking.send(MaidMenuActionPayload.of("cancelCurrent"));
                showToast("已停止当前任务");
            }
            default -> {
            }
        }
    }

    private void sendCmd(int action) {
        ClientPlayNetworking.send(new MaidCommandPayload(action));
    }

    private void showToast(String s) {
        this.toast = s;
        this.toastUntil = System.currentTimeMillis() + 1500;
    }

    @Override
    public void removed() {
        super.removed();
        if (ACTIVE == this) {
            ACTIVE = null;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
