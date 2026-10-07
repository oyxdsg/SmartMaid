package com.oyxdsg.smartmaid.client.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oyxdsg.smartmaid.client.SmartMaidClient;
import com.oyxdsg.smartmaid.client.gui.MaidMenuScreen;
import com.oyxdsg.smartmaid.client.gui.MaidSubScreen;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.test.MaidErrorSink;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.entity.Entity;

/**
 * 客户端自动化测试运行器 —— **文件驱动，全程不碰真实鼠标/键盘**。
 *
 * <h2>为什么需要它</h2>
 * 既有的 {@code MaidAutoTest} 跑在**服务端**线程，覆盖不到 GUI 与键位 —— 而 26.3 迁移中
 * 恰恰是这两处出问题（GLFW→SDL 导致键码全变、Screen 打不开）。本运行器补上客户端侧。
 *
 * <h2>怎么用</h2>
 * 把 {@code tools/clienttest.<名字>.json} 复制成
 * {@code <gameDir>/config/smartmaid/clienttest.json}，进游戏（要有世界）即可自动跑；
 * 跑完写 {@code clienttest.report.json}，并把输入文件改名成 {@code clienttest.done.json}。
 * 也可用 {@code python tools/clienttest.py run <名字>} 一条命令完成放置 + 结果读取。
 *
 * <h2>支持的 op</h2>
 * <pre>
 *   wait              {"ticks":20}                         等待若干 tick
 *   assertKeyMapping  {"expect":"key.keyboard.e"}          断言 OPEN_INVENTORY 注册的键（默认键）
 *   pollKeys          {"ticks":60,"probe":["shift","e"]}   采样真实按键状态（验证编码是否正确）
 *   openMenu          {"maidId":-1,"food":20}              直接打开女仆主菜单（等价于收到 S2C 包）
 *   clickEntry        {"index":0}                          在 MaidMenuScreen 上点第 i 个入口
 *   clickAction       {"index":0}                          在 MaidMenuScreen 上点第 i 个高频动作
 *   clickAt           {"x":100,"y":80}                     在任意位置点一下（命中测试用）
 *   clickBack         {}                                   点二级页的「返回」
 *   assertScreen      {"expect":"MaidTaskScreen"}          断言当前 Screen 的简单类名
 *   invokeOpenInventory {}                                 直接跑「Shift+E 开背包」的处理逻辑
 *   closeScreen       {}                                   关闭当前 Screen
 *   note              {"msg":"..."}                        写一条信息进报告
 * </pre>
 *
 * <h2>安全约束（重要）</h2>
 * <ol>
 *   <li>**任何异常都在本类内被吞掉并上报**，绝不让测试把游戏搞崩；</li>
 *   <li>不注入真实输入设备事件，只调用 Screen 的公开输入回调（{@code mouseClicked} 等）；</li>
 *   <li>没放测试文件时，{@link #tick} 是一次目录存在性检查，零开销。</li>
 * </ol>
 */
public final class MaidClientTest {

    private static final String IN_NAME = "clienttest.json";
    private static final String REPORT_NAME = "clienttest.report.json";
    private static final String DONE_NAME = "clienttest.done.json";

    private static List<JsonObject> cases;
    private static JsonArray results;
    private static String testName = "(unnamed)";
    private static int index;
    private static int waitTicks;
    private static boolean finished;
    private static Path inFile;
    private static Path reportFile;
    private static Path doneFile;
    private static long startedAt;

    /** 连续按键采样状态（pollKeys 用）。 */
    private static int pollTicks;
    private static final java.util.Map<String, Boolean> pollSeen = new java.util.HashMap<>();

    private MaidClientTest() {
    }

    private static Path configDir() {
        return MaidErrorSink.gameDir().resolve("config").resolve("smartmaid");
    }

    /** 每客户端 tick 调用一次。没放测试文件时是廉价的空操作。 */
    public static void tick(Minecraft mc) {
        if (finished || mc == null) {
            return;
        }
        try {
            // ⚠️ **必须已在世界中**才能跑。原因不是洁癖：本运行器会开 Screen，
            //    而 Screen 会被 Gui 持续 tick，其中有些逻辑要发包 —— 在标题画面/断线状态下
            //    发包会让 Fabric 抛 "Cannot send packets when not in game!"，直接把游戏崩掉。
            //    （实测踩过：脚本在标题画面就跑了 openMenu，20 tick 后崩溃。）
            if (!inGame(mc)) {
                noteWaitingOnce();
                return;
            }
            if (cases == null && !load()) {
                return;
            }
            if (waitTicks > 0) {
                waitTicks--;
                return;
            }
            if (pollTicks > 0) {
                samplePoll(mc);
                return;
            }
            if (index >= cases.size()) {
                finish();
                return;
            }
            runCase(mc, cases.get(index++));
        } catch (Throwable t) {
            MaidErrorSink.report("clienttest/lifecycle", "测试运行器自身异常", t);
            addResult(new Result("(runner)", "tick", "ERROR").error(t));
            finish();
        }
    }

    // ------------------------------------------------------------------ 加载 / 收尾

    /** 是否已经在世界里（有连接 + 有客户端关卡）。测试只在此条件下运行。 */
    public static boolean inGame(Minecraft mc) {
        return mc != null && mc.level != null && mc.getConnection() != null;
    }

    private static boolean waitingNoted;

    private static void noteWaitingOnce() {
        if (waitingNoted || !Files.isRegularFile(configDir().resolve(IN_NAME))) {
            return;
        }
        waitingNoted = true;
        MaidErrorSink.note("clienttest",
                "检测到 " + IN_NAME + "，但当前不在世界中 —— 进世界后会自动开始（这是有意为之："
                        + "标题画面下开 Screen 会因发包而崩溃）");
    }

    private static boolean load() {
        Path dir = configDir();
        inFile = dir.resolve(IN_NAME);
        reportFile = dir.resolve(REPORT_NAME);
        doneFile = dir.resolve(DONE_NAME);
        if (!Files.isRegularFile(inFile)) {
            finished = true;            // 没有测试文件 → 本会话不再检查
            return false;
        }
        try {
            String raw = Files.readString(inFile, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            testName = root.has("name") ? root.get("name").getAsString() : "(unnamed)";
            cases = new ArrayList<>();
            for (var e : root.getAsJsonArray("cases")) {
                cases.add(e.getAsJsonObject());
            }
            results = new JsonArray();
            index = 0;
            waitTicks = 0;
            startedAt = System.currentTimeMillis();
            MaidErrorSink.note("clienttest", "开始客户端测试: " + testName + "（" + cases.size() + " 个用例）");
            return true;
        } catch (Throwable t) {
            MaidErrorSink.report("clienttest/load", "测试脚本解析失败: " + inFile, t);
            cases = new ArrayList<>();
            results = new JsonArray();
            addResult(new Result("(load)", "parse", "ERROR").error(t));
            finish();
            return false;
        }
    }

    private static void finish() {
        if (finished) {
            return;
        }
        finished = true;
        try {
            JsonObject out = new JsonObject();
            out.addProperty("name", testName);
            out.addProperty("side", "client");
            out.addProperty("minecraft", MaidErrorSink.mcVersion());
            out.addProperty("modVersion", MaidErrorSink.modVersion());
            out.addProperty("startedAt", java.time.Instant.ofEpochMilli(startedAt).toString());
            out.addProperty("finishedAt", java.time.Instant.now().toString());
            out.addProperty("elapsedMs", System.currentTimeMillis() - startedAt);
            int pass = 0;
            int fail = 0;
            int error = 0;
            for (var e : results) {
                String s = e.getAsJsonObject().get("status").getAsString();
                switch (s) {
                    case "PASS" -> pass++;
                    case "FAIL" -> fail++;
                    default -> error++;
                }
            }
            JsonObject sum = new JsonObject();
            sum.addProperty("total", results.size());
            sum.addProperty("pass", pass);
            sum.addProperty("fail", fail);
            sum.addProperty("error", error);
            sum.addProperty("verdict", error > 0 ? "ERROR" : (fail > 0 ? "FAIL" : "PASS"));
            out.add("summary", sum);
            out.add("cases", results);
            out.add("env", env(Minecraft.getInstance()));
            Files.createDirectories(reportFile.getParent());
            Files.writeString(reportFile, GSON.toJson(out), StandardCharsets.UTF_8);
            if (Files.isRegularFile(inFile)) {
                Files.move(inFile, doneFile, StandardCopyOption.REPLACE_EXISTING);
            }
            MaidErrorSink.note("clienttest", String.format(
                    "客户端测试结束: %s（%d 用例：PASS=%d FAIL=%d ERROR=%d）→ %s",
                    testName, results.size(), pass, fail, error, reportFile));
        } catch (IOException e) {
            MaidErrorSink.report("clienttest/finish", "测试报告写入失败", e);
        }
    }

    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder()
            .setPrettyPrinting().disableHtmlEscaping().create();

    // ------------------------------------------------------------------ 用例执行

    private static void runCase(Minecraft mc, JsonObject c) {
        String op = str(c, "op", "?");
        String id = str(c, "id", op + "#" + index);
        Result r = new Result(id, op, "PASS");
        try {
            switch (op) {
                case "wait" -> {
                    waitTicks = num(c, "ticks", 20);
                    r.detail = "等待 " + waitTicks + " tick";
                }
                case "note" -> r.detail = str(c, "msg", "");
                case "assertKeyMapping" -> assertKeyMapping(mc, c, r);
                case "pollKeys" -> startPoll(c, r);
                case "openMenu" -> {
                    int maidId = num(c, "maidId", nearestMaidId(mc));
                    int food = num(c, "food", 20);
                    mc.setScreenAndShow(new MaidMenuScreen(maidId, food));
                    r.detail = "setScreenAndShow(MaidMenuScreen maidId=" + maidId + " food=" + food + ")";
                }
                case "clickEntry" -> {
                    if (!(mc.gui.screen() instanceof MaidMenuScreen m)) {
                        r.fail("当前不是 MaidMenuScreen，而是 " + screenName(mc));
                    } else {
                        int i = num(c, "index", 0);
                        int[] rect = m.entryRectForTest(i);
                        r.detail = clickAt(mc, rect, i, "入口");
                        if (r.detail == null) {
                            r.fail("入口索引越界: " + i);
                        }
                    }
                }
                case "clickAction" -> {
                    if (!(mc.gui.screen() instanceof MaidMenuScreen m)) {
                        r.fail("当前不是 MaidMenuScreen，而是 " + screenName(mc));
                    } else {
                        int i = num(c, "index", 0);
                        int[] rect = m.actionRectForTest(i);
                        r.detail = clickAt(mc, rect, i, "动作");
                        if (r.detail == null) {
                            r.fail("动作索引越界: " + i);
                        }
                    }
                }
                case "clickBack" -> {
                    if (!(mc.gui.screen() instanceof MaidSubScreen sub)) {
                        r.fail("当前不是二级页，而是 " + screenName(mc));
                    } else {
                        r.detail = clickAt(mc, sub.backRectForTest(), 0, "返回");
                    }
                }
                case "clickAt" -> {
                    Screen sc = mc.gui.screen();
                    if (sc == null) {
                        r.fail("当前没有打开的 Screen");
                    } else {
                        double x = c.get("x").getAsDouble();
                        double y = c.get("y").getAsDouble();
                        boolean handled = sc.mouseClicked(
                                new MouseButtonEvent(x, y, new MouseButtonInfo(com.oyxdsg.smartmaid.compat.MaidCompat.primaryMouseButton(), 0)), false);
                        r.detail = String.format("clickAt(%.0f,%.0f) handled=%s", x, y, handled);
                    }
                }
                case "assertScreen" -> {
                    String expect = str(c, "expect", "");
                    String actual = screenName(mc);
                    if (expect.equals(actual)) {
                        r.detail = "当前 Screen = " + actual;
                    } else {
                        r.fail("期望 Screen=" + expect + "，实际=" + actual);
                    }
                }
                case "invokeOpenInventory" -> {
                    boolean handled = SmartMaidClient.tryOpenMaidInventory();
                    r.detail = "tryOpenMaidInventory() = " + handled
                            + "（false 通常表示此刻没有真的按住 Shift —— 这是输入状态，不是缺陷）";
                }
                case "closeScreen" -> {
                    Screen sc = mc.gui.screen();
                    if (sc == null) {
                        r.detail = "本就没有 Screen";
                    } else {
                        sc.onClose();
                        r.detail = "已关闭 " + sc.getClass().getSimpleName();
                    }
                }
                default -> r.fail("未知 op: " + op);
            }
        } catch (Throwable t) {
            r.error(t);
        }
        if (r.detail == null) {
            r.detail = "";
        }
        if (pollTicks <= 0) {
            addResult(r);
        }
    }

    private static void assertKeyMapping(Minecraft mc, JsonObject c, Result r) {
        KeyMapping km = SmartMaidClient.OPEN_INVENTORY;
        var key = km.getDefaultKey();
        String actualName = key.getName();
        int actualValue = key.getValue();
        String actualType = String.valueOf(key.getType());
        String expect = str(c, "expect", null);
        r.detail = String.format("name=%s value=%d type=%s", actualName, actualValue, actualType);
        if (expect != null && !expect.equals(actualName)) {
            r.fail("期望键名 " + expect + "，实际 " + actualName + "（value=" + actualValue + "）");
        }
    }

    private static String clickAt(Minecraft mc, int[] rect, int idx, String what) {
        if (rect == null) {
            return null;
        }
        Screen sc = mc.gui.screen();
        if (sc == null) {
            return "（无 Screen）";
        }
        double cx = rect[0] + rect[2] / 2.0;
        double cy = rect[1] + rect[3] / 2.0;
        sc.mouseMoved(cx, cy);
        boolean handled = sc.mouseClicked(new MouseButtonEvent(cx, cy, new MouseButtonInfo(com.oyxdsg.smartmaid.compat.MaidCompat.primaryMouseButton(), 0)), false);
        String detail = String.format("点 %s#%d @(%.0f,%.0f) rect=%s handled=%s → Screen=%s",
                what, idx, cx, cy, Arrays.toString(rect), handled, screenName(mc));
        if (!handled) {
            throw new IllegalStateException("点击未被 Screen 处理：" + detail);
        }
        return detail;
    }

    // ------------------------------------------------------------------ 按键采样

    private static void startPoll(JsonObject c, Result r) {
        pollTicks = num(c, "ticks", 60);
        pollSeen.clear();
        r.detail = "开始采样按键 " + pollTicks + " tick：请在这段时间内**按住**要测的键（如 Shift、E）";
        results.add(GSON.toJsonTree(r));      // poll 先占位，结束时再更新
    }

    private static void samplePoll(Minecraft mc) {
        pollTicks--;
        int[] shifts = SmartMaidClient.shiftKeyCodes();
        pollSeen.merge("LShift(" + shifts[0] + ")",
                com.oyxdsg.smartmaid.compat.MaidCompat.isKeyDown(shifts[0]), Boolean::logicalOr);
        pollSeen.merge("RShift(" + shifts[1] + ")",
                com.oyxdsg.smartmaid.compat.MaidCompat.isKeyDown(shifts[1]), Boolean::logicalOr);
        pollSeen.merge("KeyMapping.isDown", SmartMaidClient.OPEN_INVENTORY.isDown(), Boolean::logicalOr);
        if (pollTicks > 0) {
            return;
        }
        // 更新占位结果
        JsonObject last = results.get(results.size() - 1).getAsJsonObject();
        StringBuilder sb = new StringBuilder("采样结果: ");
        boolean any = false;
        for (var e : pollSeen.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append("  ");
            any |= Boolean.TRUE.equals(e.getValue());
        }
        if (any) {
            last.addProperty("detail", sb.toString());
        } else {
            last.addProperty("status", "FAIL");
            last.addProperty("detail",
                    sb + "→ 一个键都没读到。若你确实按住了，说明键值编码不对");
            MaidErrorSink.report("clienttest/pollKeys",
                    "按键采样一个都没命中（键值编码可能仍不对）", null);
        }
    }

    // ------------------------------------------------------------------ 工具

    private static String screenName(Minecraft mc) {
        Screen s = mc.gui.screen();
        return s == null ? "(null)" : s.getClass().getSimpleName();
    }

    private static int nearestMaidId(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return -1;
        }
        double best = Double.MAX_VALUE;
        int id = -1;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof SmartMaidEntity) {
                double d = e.distanceToSqr(mc.player);
                if (d < best) {
                    best = d;
                    id = e.getId();
                }
            }
        }
        return id;
    }

    private static JsonObject env(Minecraft mc) {
        JsonObject o = new JsonObject();
        if (mc == null) {
            return o;
        }
        o.addProperty("inWorld", mc.level != null);
        o.addProperty("screen", screenName(mc));
        o.addProperty("nearestMaidId", nearestMaidId(mc));
        KeyMapping km = SmartMaidClient.OPEN_INVENTORY;
        o.addProperty("openInventoryKey", km.getDefaultKey().getName());
        o.addProperty("openInventoryKeyValue", km.getDefaultKey().getValue());
        o.addProperty("openInventoryKeyType", String.valueOf(km.getDefaultKey().getType()));
        JsonArray sh = new JsonArray();
        for (int k : SmartMaidClient.shiftKeyCodes()) {
            sh.add(k);
        }
        o.add("shiftKeyCodes", sh);
        return o;
    }

    private static void addResult(Result r) {
        results.add(GSON.toJsonTree(r));
        if (!"PASS".equals(r.status)) {
            MaidErrorSink.report("clienttest/" + r.id, r.op + " → " + r.status + "：" + r.detail, r.error);
        }
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }

    private static int num(JsonObject o, String k, int def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : def;
    }

    /** 单条用例结果（序列化成报告里的一个元素）。 */
    private static final class Result {
        private final String id;
        private final String op;
        private String status;
        private String detail;
        private transient Throwable error;

        Result(String id, String op, String status) {
            this.id = id;
            this.op = op;
            this.status = status;
        }

        Result fail(String why) {
            this.status = "FAIL";
            this.detail = why;
            return this;
        }

        Result error(Throwable t) {
            this.status = "ERROR";
            this.error = t;
            this.detail = String.valueOf(t);
            return this;
        }
    }
}
