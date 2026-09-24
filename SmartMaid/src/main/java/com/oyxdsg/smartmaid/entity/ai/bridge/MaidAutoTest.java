package com.oyxdsg.smartmaid.entity.ai.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 女仆自动测试钩子（开发用）：服务端 tick 驱动，读配置文件自动执行一条条 JSON 指令，
 * 结果写日志。用于"代码层自动化测试"，无需模拟鼠标/键盘操作游戏界面。
 *
 * <p>配置文件：{@code config/smartmaid/autotest.json}，格式为 JSON 数组：</p>
 * <pre>{@code
 * [
 *   {"id":"t01","cmd":"status"},
 *   {"id":"g1","give":{"minecraft:oak_log":8,"minecraft:iron_ingot":5}},  // 预置背包
 *   {"id":"t02","cmd":"craft_check","params":{"item":"minecraft:iron_pickaxe","count":1},
 *    "expect":{"ok":true,"result.craftable":true}}                          // 回执断言
 * ]
 * }</pre>
 *
 * <p>支持三种条目：</p>
 * <ul>
 *   <li>{@code cmd}：普通 JSON 指令（走 {@link MaidAIBridge}）</li>
 *   <li>{@code give}：给女仆背包预置物品（0-35 背包区，同类堆叠/空槽）——测试前准备材料用</li>
 *   <li>{@code expect}：可挂在 cmd 条目上，对回执做扁平字段断言（键为点分路径，如
 *       {@code ok} / {@code result.craftable}），全部匹配记 PASS，否则记 FAIL</li>
 * </ul>
 *
 * <p>流程：有玩家在线但无女仆 → 自动执行 {@code summonmaid} 召唤；有女仆 →
 * 逐条执行并记录回执；任务型指令（回执 {@code running}）执行后自动等待约 2 秒
 * 再取下一条，保证任务真正跑完（否则连续 craft 会被 {@code aiBusy} 拒绝）。
 * 全部执行完把配置文件改名为 {@code autotest.done.json} 防重复。
 * 无配置文件时完全静默（生产无影响）。</p>
 */
public final class MaidAutoTest {

    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir()
            .resolve("smartmaid").resolve("autotest.json");
    private static final AtomicBoolean EXECUTED = new AtomicBoolean(false);

    private static JsonArray LIST;        // 已加载的测试序列
    private static int IDX;               // 当前执行到第几条
    private static int WAIT_TICKS;        // 任务型指令后的等待 tick

    private static final int STEP_TICKS = 10;      // 状态机推进间隔
    private static final int TASK_WAIT_TICKS = 40; // 任务型指令后等待（≈2 秒）

    private MaidAutoTest() {
    }

    /** 由 ServerTickEvents.END_SERVER_TICK 注册调用（仅服务端触发） */
    public static void onServerTick(MinecraftServer server) {
        if (EXECUTED.get() || server.getTickCount() % STEP_TICKS != 0) {
            return;
        }
        if (!Files.exists(CONFIG)) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        if (player == null) {
            return; // 无玩家在线，等待
        }
        SmartMaidEntity maid = findMaid(player);
        if (maid == null) {
            // 无女仆：尝试召唤，下次 tick 再检测
            try {
                server.getCommands().getDispatcher().execute(
                        "summonmaid", player.createCommandSourceStack());
                MaidDebug.log("AutoTest: 未找到女仆，已执行 summonmaid");
            } catch (Exception e) {
                MaidDebug.log("AutoTest: summonmaid 失败: " + e.getMessage());
            }
            return;
        }
        run(maid);
    }

    /** 状态机：每 STEP_TICKS tick 推进一条；任务型回执后等 TASK_WAIT_TICKS。 */
    private static void run(SmartMaidEntity maid) {
        if (LIST == null && !load()) {
            return; // 配置不存在 / 加载失败（load 内已处理）
        }
        if (WAIT_TICKS > 0) {
            WAIT_TICKS--;
            return; // 等待前一条任务完成
        }
        if (IDX >= LIST.size()) {
            finish();
            return;
        }
        JsonElement element = LIST.get(IDX);
        IDX++;
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject req = element.getAsJsonObject();
        String id = req.has("id") ? req.get("id").getAsString() : "?";

        // give：预置女仆背包（AutoTest 专用，非指令）
        if (req.has("give")) {
            giveItems(maid, req.getAsJsonObject("give"));
            MaidDebug.log("AutoTest [" + id + "] give: " + req.get("give"));
            return;
        }

        String json = req.toString();
        MaidCommandResult result = MaidAIBridge.execute(maid, json);
        if (req.has("expect")) {
            boolean ok = checkExpect(req.getAsJsonObject("expect"), result);
            MaidDebug.log("AutoTest [" + id + "] 断言=" + (ok ? "PASS" : "FAIL")
                    + " 期望=" + req.get("expect") + " 实际=" + result.toJson());
        } else {
            MaidDebug.log("AutoTest [" + id + "] 回执=" + result.toJson());
        }
        if (result.state().equals("running")) {
            WAIT_TICKS = TASK_WAIT_TICKS; // 任务型：等它跑完再取下一条
        }
    }

    private static boolean load() {
        if (!Files.exists(CONFIG)) {
            return false;
        }
        try {
            String text = Files.readString(CONFIG, StandardCharsets.UTF_8);
            LIST = JsonParser.parseString(text).getAsJsonArray();
            IDX = 0;
            WAIT_TICKS = 0;
            MaidDebug.log("AutoTest: 开始执行测试序列，共 " + LIST.size()
                    + " 条，配置文件 " + CONFIG);
            return true;
        } catch (Exception e) {
            SmartMaid.LOGGER.error("AutoTest 配置加载失败", e);
            MaidDebug.log("AutoTest: 配置加载失败: " + e.getMessage());
            EXECUTED.set(true);
            return false;
        }
    }

    private static void finish() {
        EXECUTED.set(true);
        LIST = null;
        IDX = 0;
        WAIT_TICKS = 0;
        try {
            Path done = CONFIG.resolveSibling("autotest.done.json");
            Files.move(CONFIG, done, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            MaidDebug.log("AutoTest: 执行完成，配置已标记为 " + done.getFileName());
        } catch (Exception e) {
            MaidDebug.log("AutoTest: 标记完成失败: " + e.getMessage());
        }
    }

    /** 给女仆背包预置物品（0-35 背包区，同类堆叠/空槽）。 */
    private static void giveItems(SmartMaidEntity maid, JsonObject give) {
        for (Map.Entry<String, JsonElement> e : give.entrySet()) {
            String itemId = e.getKey();
            int count;
            try {
                count = e.getValue().getAsInt();
            } catch (NumberFormatException ex) {
                MaidDebug.log("AutoTest give: 数量非法 " + itemId);
                continue;
            }
            if (count <= 0) {
                continue;
            }
            Item item = resolveItem(itemId);
            if (item == null) {
                MaidDebug.log("AutoTest give: 未找到物品 " + itemId);
                continue;
            }
            ItemStack left = MaidActions.storeToBackpack(maid, new ItemStack(item, count));
            if (!left.isEmpty()) {
                MaidDebug.log("AutoTest give: " + itemId + " x" + count
                        + " 背包满，剩余 " + left.getCount());
            }
        }
        maid.getMaidInventory().setChanged();
    }

    private static Item resolveItem(String id) {
        Identifier ident = Identifier.tryParse(id);
        if (ident == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(ident).map(Holder::value).orElse(null);
        return (item == null || item == Items.AIR) ? null : item;
    }

    /**
     * 断言回执：expect 为扁平 JSON，键是点分路径（如 {@code ok} / {@code result.craftable}），
     * 值是与回执对应字段的期望值；全部匹配返回 true。
     */
    private static boolean checkExpect(JsonObject expect, MaidCommandResult result) {
        JsonObject actual;
        try {
            actual = JsonParser.parseString(result.toJson()).getAsJsonObject();
        } catch (Exception e) {
            return false;
        }
        for (Map.Entry<String, JsonElement> e : expect.entrySet()) {
            JsonElement got = getByPath(actual, e.getKey());
            JsonElement expected = e.getValue();
            if (got == null || !jsonEquals(got, expected)) {
                MaidDebug.log("AutoTest 断言失败: " + e.getKey()
                        + " 期望=" + expected + " 实际=" + (got == null ? "null" : got));
                return false;
            }
        }
        return true;
    }

    private static JsonElement getByPath(JsonObject root, String path) {
        JsonElement cur = root;
        for (String part : path.split("\\.")) {
            if (cur == null || !cur.isJsonObject()) {
                return null;
            }
            cur = cur.getAsJsonObject().get(part);
        }
        return cur;
    }

    private static boolean jsonEquals(JsonElement got, JsonElement expected) {
        if (got.isJsonPrimitive() && expected.isJsonPrimitive()) {
            JsonPrimitive g = got.getAsJsonPrimitive();
            JsonPrimitive e = expected.getAsJsonPrimitive();
            if (g.isBoolean() || e.isBoolean()) {
                return g.isBoolean() && e.isBoolean()
                        && g.getAsBoolean() == e.getAsBoolean();
            }
            if (g.isNumber() && e.isNumber()) {
                return g.getAsBigDecimal().compareTo(e.getAsBigDecimal()) == 0;
            }
            return g.getAsString().equals(e.getAsString());
        }
        return got.equals(expected);
    }

    private static SmartMaidEntity findMaid(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return null;
        }
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof SmartMaidEntity maid && maid.isOwnedBy(player)) {
                return maid;
            }
        }
        return null;
    }
}
