package com.oyxdsg.smartmaid.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.network.MaidMenuActionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 二级「添加任务」页（N1 步骤 4）：任务网格 + 就地参数面板（数字 stepper / 坐标三态「准星·女仆脚下」）。
 */
public class MaidAddTaskScreen extends MaidSubScreen {

    /** 参数规格：键、默认、上下限。 */
    private record Spec(String key, int def, int min, int max) {
    }

    /** 快捷任务：显示名、指令、数字参数、是否需要坐标。 */
    private record Quick(String label, String cmd, List<Spec> specs, boolean needsPos) {
    }

    private static final List<Quick> QUICKS = List.of(
            new Quick("挖矿", "mine", List.of(new Spec("range", 12, 1, 32), new Spec("count", 8, 1, 64)), false),
            new Quick("护卫", "guard", List.of(new Spec("range", 10, 1, 32)), false),
            new Quick("收集", "collect", List.of(new Spec("range", 8, 1, 32)), false),
            new Quick("进食", "eat", List.of(), false),
            new Quick("攻击最近", "attack", List.of(new Spec("range", 12, 1, 32)), false),
            new Quick("移动", "move", List.of(), true),
            new Quick("耕作", "farm", List.of(new Spec("range", 4, 1, 16)), true),
            new Quick("存箱", "cheststore", List.of(new Spec("count", 64, 1, 64)), true));

    private record Hot(int x, int y, int w, int h, Runnable action) {
    }

    private final List<Hot> hots = new ArrayList<>();
    private int selected = -1;
    private final int[] values = new int[4];
    private int posMode; // 0 = 准星，1 = 女仆脚下

    public MaidAddTaskScreen(Screen parent, JsonObject state) {
        super(parent, Component.literal("添加任务"));
    }

    @Override
    protected void renderContent(GuiGraphicsExtractor ex, int mouseX, int mouseY, float tickDelta) {
        this.hots.clear();
        if (selected < 0) {
            renderGrid(ex);
        } else {
            renderParams(ex, QUICKS.get(selected));
        }
    }

    private void renderGrid(GuiGraphicsExtractor ex) {
        ex.text(this.font, Component.literal("点选任务，再设参数追加到短期队列"),
                this.left + 16, this.top + 38, MaidTheme.TEXT_3);
        int x = this.left + 16;
        int y = this.top + 56;
        int w = (PANEL_W - 32 - 8) / 2;
        int h = 24;
        for (int i = 0; i < QUICKS.size(); i++) {
            int cx = x + (i % 2) * (w + 8);
            int cy = y + (i / 2) * (h + 8);
            final int idx = i;
            MaidTheme.roundRect(ex, cx, cy, cx + w, cy + h, MaidTheme.CARD, 0);
            ex.text(this.font, Component.literal(QUICKS.get(i).label()), cx + 10, cy + 8, MaidTheme.TEXT);
            this.hots.add(new Hot(cx, cy, w, h, () -> select(idx)));
        }
    }

    private void select(int idx) {
        this.selected = idx;
        List<Spec> specs = QUICKS.get(idx).specs();
        for (int i = 0; i < values.length; i++) {
            values[i] = i < specs.size() ? specs.get(i).def() : 0;
        }
        this.posMode = 0;
    }

    private void renderParams(GuiGraphicsExtractor ex, Quick q) {
        int y = this.top + 40;
        ex.text(this.font, Component.literal("任务：" + q.label()), this.left + 16, y, MaidTheme.TEXT);
        y += 18;
        List<Spec> specs = q.specs();
        for (int i = 0; i < specs.size(); i++) {
            Spec s = specs.get(i);
            ex.text(this.font, Component.literal(s.key()), this.left + 16, y + 5, MaidTheme.TEXT_2);
            int bx = this.left + 110;
            final int idx = i;
            button(ex, bx, y, 20, "-", () -> values[idx] = Math.max(s.min(), values[idx] - step(s)));
            ex.text(this.font, Component.literal(String.valueOf(values[i])), bx + 30, y + 6, MaidTheme.TEXT);
            button(ex, bx + 56, y, 20, "+", () -> values[idx] = Math.min(s.max(), values[idx] + step(s)));
            y += 26;
        }

        if (q.needsPos()) {
            ex.text(this.font, Component.literal("坐标"), this.left + 16, y + 5, MaidTheme.TEXT_2);
            int bx = this.left + 110;
            button(ex, bx, y, 60, posMode == 0 ? "[准星]✓" : "准星",
                    () -> posMode = 0);
            button(ex, bx + 66, y, 78, posMode == 1 ? "[女仆脚下]✓" : "女仆脚下",
                    () -> posMode = 1);
            y += 26;
            if (posMode == 0 && crosshairBlock() == null) {
                ex.text(this.font, Component.literal("（准星未指向方块）"), this.left + 16, y, MaidTheme.TEXT_3);
                y += 14;
            }
        }

        int by = this.top + PANEL_H - 26;
        button(ex, this.left + 16, by, 84, "取消", () -> this.selected = -1);
        button(ex, this.left + PANEL_W - 16 - 84, by, 84, "追加到队列", this::submit);
    }

    private static int step(Spec s) {
        return (s.max() - s.min()) >= 32 ? 4 : 1;
    }

    private void submit() {
        Quick q = QUICKS.get(this.selected);
        JsonObject p = new JsonObject();
        List<Spec> specs = q.specs();
        for (int i = 0; i < specs.size(); i++) {
            p.addProperty(specs.get(i).key(), values[i]);
        }
        if (q.needsPos()) {
            if (posMode == 1) {
                JsonArray a = new JsonArray();
                a.add("~");
                a.add("~");
                a.add("~");
                p.add("pos", a);
            } else {
                int[] b = crosshairBlock();
                if (b == null) {
                    return;
                }
                JsonArray a = new JsonArray();
                a.add(b[0]);
                a.add(b[1]);
                a.add(b[2]);
                p.add("pos", a);
            }
        }
        MaidNet.send(MaidMenuActionPayload.enqueue(q.cmd(), p.toString()));
        goBack();
    }

    /** 准星所指方块（客户端射线，超出 8 格或未命中返回 null）。 */
    private static int[] crosshairBlock() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return null;
        }
        Vec3 from = mc.player.getEyePosition();
        Vec3 to = from.add(mc.player.getViewVector(1.0F).scale(8.0D));
        BlockHitResult hit = mc.player.level().clip(new ClipContext(from, to,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return null;
        }
        BlockPos bp = hit.getBlockPos();
        return new int[]{bp.getX(), bp.getY(), bp.getZ()};
    }

    private void button(GuiGraphicsExtractor ex, int x, int y, int w, String label, Runnable action) {
        MaidTheme.roundRect(ex, x, y, x + w, y + 20, MaidTheme.BTN, 0);
        ex.centeredText(this.font, Component.literal(label), x + w / 2, y + 6, MaidTheme.CARD);
        this.hots.add(new Hot(x, y, w, 20, action));
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
