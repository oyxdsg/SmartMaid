package com.oyxdsg.smartmaid.entity.ai.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidInventoryTidy;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>支持五种条目（2026-09-29 扩展 check / run）：</p>
 * <ul>
 *   <li>{@code cmd}：普通 JSON 指令（走 {@link MaidAIBridge}）</li>
 *   <li>{@code give}：给女仆背包预置物品（0-35 背包区，同类堆叠/空槽）——测试前准备材料用</li>
 *   <li>{@code expect}：可挂在 cmd 条目上，对回执做扁平字段断言（键为点分路径，如
 *       {@code ok} / {@code result.craftable}），全部匹配记 PASS，否则记 FAIL</li>
 *   <li>{@code run}：执行一条命令（测试准备用，如 setblock 造环境）；用服务器控制台源执行
 *       （不需 OP），{@code {x}}/{@code {y}}/{@code {z}} 会替换为女仆方块坐标</li>
 *   <li>{@code check}：对世界/背包/规则做断言（见 {@link #runCheck}），用于验证"指令执行后
 *       世界真的变了"—— 例如方块是否被破坏、掉落是否进了背包、搭路白名单判定、数据包 tag 是否生效</li>
 * </ul>
 *
 * <pre>{@code
 * [
 *   {"id":"p1","run":"setblock {x} {y} {z} minecraft:stone"},              // 在女仆脚下造方块
 *   {"id":"b1","cmd":"break","params":{"pos":[x,y,z]}},                    // 让女仆挖掉它
 *   {"id":"c1","check":{"block":{"offset":[0,0,0],"is":"minecraft:air"}}}, // 断言已挖空
 *   {"id":"c2","check":{"inv":{"item":"minecraft:cobblestone","min":1}}},  // 断言掉落进了背包
 *   {"id":"c3","check":{"bridge":{"item":"minecraft:diamond_block","allowed":false}}}
 * ]
 * }</pre>
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

    /** 测试用：下一次破坏是否被 BEFORE 事件否决（验证"破坏走原版流程、保护可拦截"） */
    private static volatile boolean vetoNextBreak;

    /** 断言计数（结束时输出汇总，省得逐行数） */
    private static int PASS_COUNT;
    private static int FAIL_COUNT;

    /**
     * 最近一次 {@code run} 里 setblock 的坐标。
     *
     * <p>存在的理由：女仆会跟随玩家移动，所以"相对女仆当前位置"的断言（offset）在等待若干秒后会指向
     * 别处 —— 第一次真机测试就栽在这里（v04 FAIL，而事件其实已被正确否决）。
     * 用绝对坐标做断言/清理才稳定，故提供 {@code check:{"at":"lastRun"}} 与 {@code {lx}/{ly}/{lz}} 占位符。</p>
     */
    private static BlockPos lastRunBlock;

    /** 匹配 run 命令里的 setblock 坐标 */
    private static final Pattern SETBLOCK_POS =
            Pattern.compile("setblock\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)");

    static {
        // 注册"测试否决器"：只有配置显式打开 vetoNextBreak 时才拦一次，
        // 用来证明 PlayerBlockBreakEvents 链路真的接通（生产环境永远不影响。
        // 领地保护等 mod 正是通过同一个事件拦截的）。
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (vetoNextBreak) {
                vetoNextBreak = false;
                SmartMaid.LOGGER.info("AutoTest: 破坏被事件否决 @ " + pos);
                return false;
            }
            return true;
        });
    }

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
                SmartMaid.LOGGER.info("AutoTest: 未找到女仆，已执行 summonmaid");
            } catch (Exception e) {
                SmartMaid.LOGGER.info("AutoTest: summonmaid 失败: " + e.getMessage());
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

        // 纯注释条目（如 {"__comment": "..."}）跳过，不当作指令执行
        if (!req.has("cmd") && !req.has("give") && !req.has("run") && !req.has("check")
                && !req.has("tidy") && !req.has("vetoNextBreak") && !req.has("expect")) {
            return;
        }

        // vetoNextBreak：武装"测试否决器"，用于验证破坏事件链（见静态块）
        if (req.has("vetoNextBreak")) {
            vetoNextBreak = req.get("vetoNextBreak").getAsBoolean();
            SmartMaid.LOGGER.info("AutoTest [" + id + "] vetoNextBreak=" + vetoNextBreak);
            return;
        }

        // give：预置女仆背包（AutoTest 专用，非指令）
        if (req.has("give")) {
            giveItems(maid, req.getAsJsonObject("give"));
            SmartMaid.LOGGER.info("AutoTest [" + id + "] give: " + req.get("give"));
            return;
        }

        // tidy：立即执行一次背包整理（合并同类 + 丢弃 #smartmaid:junk）
        if (req.has("tidy")) {
            MaidInventoryTidy.Result r = MaidInventoryTidy.tidy(maid);
            SmartMaid.LOGGER.info("AutoTest [" + id + "] tidy: 合并=" + r.merged()
                    + " 丢弃=" + r.dropped() + " 空槽=" + freeSlots(maid));
            return;
        }

        // run：执行一条命令（测试准备用，如 setblock 造环境）。
        // 用服务器控制台源执行（权限最高），{x}/{y}/{z} 会替换为女仆方块坐标。
        if (req.has("run")) {
            String cmd = req.get("run").getAsString();
            runCommand(maid, id, cmd);
            return;
        }

        // check：对世界/背包/规则做断言（AutoTest 专用，非指令）
        if (req.has("check")) {
            JsonObject check = req.getAsJsonObject("check");
            boolean ok = runCheck(maid, check);
            count(ok);
            SmartMaid.LOGGER.info("AutoTest [" + id + "] 检查=" + (ok ? "PASS" : "FAIL") + " " + check);
            return;
        }

        // 指令 JSON 同样支持位置占位符（{lx}/{ly}/{lz}、{x}/{x+1}…），
        // 这样 break 能用绝对坐标瞄准"刚 setblock 的那个方块"，不受女仆移动影响
        String json = expandPos(maid, req.toString());
        MaidCommandResult result = MaidAIBridge.execute(maid, json);
        if (req.has("expect")) {
            boolean ok = checkExpect(req.getAsJsonObject("expect"), result);
            count(ok);
            SmartMaid.LOGGER.info("AutoTest [" + id + "] 断言=" + (ok ? "PASS" : "FAIL")
                    + " 期望=" + req.get("expect") + " 实际=" + result.toJson());
        } else {
            SmartMaid.LOGGER.info("AutoTest [" + id + "] 回执=" + result.toJson());
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
            PASS_COUNT = 0;
            FAIL_COUNT = 0;
            SmartMaid.LOGGER.info("AutoTest: 开始执行测试序列，共 " + LIST.size()
                    + " 条，配置文件 " + CONFIG);
            return true;
        } catch (Exception e) {
            SmartMaid.LOGGER.error("AutoTest 配置加载失败", e);
            SmartMaid.LOGGER.info("AutoTest: 配置加载失败: " + e.getMessage());
            EXECUTED.set(true);
            return false;
        }
    }

    private static void finish() {
        EXECUTED.set(true);
        LIST = null;
        IDX = 0;
        WAIT_TICKS = 0;
        // 汇总行：测试结果走 LOGGER（不能用 MaidDebug —— 它默认关闭，会把结果全静音）
        SmartMaid.LOGGER.info("AutoTest 汇总: PASS=" + PASS_COUNT + " FAIL=" + FAIL_COUNT
                + (FAIL_COUNT == 0 ? " （全部通过）" : " （有失败项，见上面 FAIL 行）"));
        try {
            Path done = CONFIG.resolveSibling("autotest.done.json");
            Files.move(CONFIG, done, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            SmartMaid.LOGGER.info("AutoTest: 执行完成，配置已标记为 " + done.getFileName());
        } catch (Exception e) {
            SmartMaid.LOGGER.info("AutoTest: 标记完成失败: " + e.getMessage());
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
                SmartMaid.LOGGER.info("AutoTest give: 数量非法 " + itemId);
                continue;
            }
            if (count <= 0) {
                continue;
            }
            Item item = resolveItem(itemId);
            if (item == null) {
                SmartMaid.LOGGER.info("AutoTest give: 未找到物品 " + itemId);
                continue;
            }
            ItemStack left = MaidActions.storeToBackpack(maid, new ItemStack(item, count));
            if (!left.isEmpty()) {
                SmartMaid.LOGGER.info("AutoTest give: " + itemId + " x" + count
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

    /** 位置占位符：{@code {x}} / {@code {x+1}} / {@code {y-2}} …（相对女仆方块坐标，支持加减偏移） */
    private static final Pattern POS_TOKEN = Pattern.compile("\\{([xyz])([+-]\\d+)?\\}");

    private static String expandPos(SmartMaidEntity maid, String template) {
        BlockPos p = maid.blockPosition();
        String s = template;
        // {lx}/{ly}/{lz}：最近一次 run 里 setblock 的坐标（不随女仆移动而变）
        if (lastRunBlock != null) {
            s = s.replace("{lx}", String.valueOf(lastRunBlock.getX()))
                    .replace("{ly}", String.valueOf(lastRunBlock.getY()))
                    .replace("{lz}", String.valueOf(lastRunBlock.getZ()));
        }
        Matcher m = POS_TOKEN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int base = switch (m.group(1)) {
                case "x" -> p.getX();
                case "y" -> p.getY();
                default -> p.getZ();
            };
            int delta = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            m.appendReplacement(sb, String.valueOf(base + delta));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 执行一条命令（服务器控制台源，权限最高；位置占位符见 {@link #expandPos}） */
    private static void runCommand(SmartMaidEntity maid, String id, String cmdTemplate) {
        String cmd = expandPos(maid, cmdTemplate);
        // 记录 setblock 坐标，供后续 check:{"at":"lastRun"} 与 {lx}/{ly}/{lz} 引用
        Matcher sb = SETBLOCK_POS.matcher(cmd);
        if (sb.find()) {
            lastRunBlock = new BlockPos(Integer.parseInt(sb.group(1)),
                    Integer.parseInt(sb.group(2)), Integer.parseInt(sb.group(3)));
        }
        MinecraftServer server = maid.level().getServer();
        if (server == null) {
            SmartMaid.LOGGER.info("AutoTest [" + id + "] run: 无服务器");
            return;
        }
        try {
            int r = server.getCommands().getDispatcher()
                    .execute(cmd, server.createCommandSourceStack());
            SmartMaid.LOGGER.info("AutoTest [" + id + "] run: " + cmd + " -> " + r);
        } catch (Exception e) {
            SmartMaid.LOGGER.info("AutoTest [" + id + "] run 失败: " + cmd + " : " + e.getMessage());
        }
    }

    /** 断言计数（结束输出汇总用） */
    private static void count(boolean ok) {
        if (ok) {
            PASS_COUNT++;
        } else {
            FAIL_COUNT++;
        }
    }

    /**
     * 执行一条检查断言（AutoTest 专用，不产生指令回执）。支持的类型：
     *
     * <pre>{@code
     * {"check": {"block":  {"offset":[1,-1,0], "is":"minecraft:air"}}}       // 女仆脚下偏移处（或 pos:[x,y,z]）
     * {"check": {"inv":    {"item":"minecraft:dirt", "min":1}}}              // 背包（0-35）中该物品总数
     * {"check": {"freeSlots": {"min":1}}}                                      // 背包区空槽数
     * {"check": {"bridge": {"item":"minecraft:diamond_block","allowed":false}}} // 搭路白名单判定
     * {"check": {"tag":    {"tag":"smartmaid:never_target","entity":"minecraft:enderman","contains":true}}}
     * }</pre>
     */
    private static boolean runCheck(SmartMaidEntity maid, JsonObject check) {
        try {
            if (check.has("block")) {
                return checkBlock(maid, check.getAsJsonObject("block"));
            }
            if (check.has("inv")) {
                return checkInv(maid, check.getAsJsonObject("inv"));
            }
            if (check.has("bridge")) {
                return checkBridge(check.getAsJsonObject("bridge"));
            }
            if (check.has("tag")) {
                return checkTag(check.getAsJsonObject("tag"));
            }
        } catch (Exception e) {
            SmartMaid.LOGGER.info("AutoTest check 异常: " + e);
            return false;
        }
        SmartMaid.LOGGER.info("AutoTest check: 未识别的检查类型 " + check);
        return false;
    }

    /** 方块状态断言：{@code is} 为期望的方块 id（缺省只要求"能读到"） */
    private static boolean checkBlock(SmartMaidEntity maid, JsonObject c) {
        BlockPos pos = posOf(maid, c);
        if (pos == null) {
            SmartMaid.LOGGER.info("checkBlock: 缺少 pos 或 offset");
            return false;
        }
        BlockState state = maid.level().getBlockState(pos);
        Identifier actual = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String want = c.has("is") ? c.get("is").getAsString() : null;
        boolean ok = want == null || want.equals(String.valueOf(actual));
        SmartMaid.LOGGER.info("checkBlock " + pos + " 实际=" + actual + " 期望=" + want + " -> " + ok);
        return ok;
    }

    private static BlockPos posOf(SmartMaidEntity maid, JsonObject c) {
        // at:lastRun —— 用最近一次 run 里 setblock 的绝对坐标（女仆移动也不影响断言）
        if (c.has("at") && "lastRun".equals(c.get("at").getAsString())) {
            if (lastRunBlock == null) {
                SmartMaid.LOGGER.info("AutoTest checkBlock: at=lastRun 但还没有 run 过 setblock");
            }
            return lastRunBlock;
        }
        if (c.has("offset") && c.get("offset").isJsonArray()) {
            JsonArray a = c.getAsJsonArray("offset");
            return maid.blockPosition().offset(
                    a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
        }
        if (c.has("pos") && c.get("pos").isJsonArray()) {
            JsonArray a = c.getAsJsonArray("pos");
            return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
        }
        return null;
    }

    /** 背包数量断言：{@code min} / {@code max} / {@code is}（三者可任选） */
    private static boolean checkInv(SmartMaidEntity maid, JsonObject c) {
        String itemId = c.get("item").getAsString();
        Item item = resolveItem(itemId);
        if (item == null) {
            SmartMaid.LOGGER.info("checkInv: 未找到物品 " + itemId);
            return false;
        }
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = maid.getMaidInventory().getItem(i);
            if (s.is(item)) {
                total += s.getCount();
            }
        }
        boolean ok = true;
        if (c.has("min")) {
            ok &= total >= c.get("min").getAsInt();
        }
        if (c.has("max")) {
            ok &= total <= c.get("max").getAsInt();
        }
        if (c.has("is")) {
            ok &= total == c.get("is").getAsInt();
        }
        SmartMaid.LOGGER.info("checkInv " + itemId + " 实际=" + total + " -> " + ok);
        return ok;
    }

    /** 背包区（0-35）空槽数 */
    private static int freeSlots(SmartMaidEntity maid) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            if (maid.getMaidInventory().getItem(i).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** 空槽数断言：{@code min} / {@code max} / {@code is}（三者可任选） */
    private static boolean checkFreeSlots(SmartMaidEntity maid, JsonObject c) {
        int free = freeSlots(maid);
        boolean ok = true;
        if (c.has("min")) {
            ok &= free >= c.get("min").getAsInt();
        }
        if (c.has("max")) {
            ok &= free <= c.get("max").getAsInt();
        }
        if (c.has("is")) {
            ok &= free == c.get("is").getAsInt();
        }
        SmartMaid.LOGGER.info("checkFreeSlots 空槽=" + free + " 期望=" + c + " -> " + ok);
        return ok;
    }

    /** 搭路白名单断言：断言某物品是否被判定为"可消耗的建材" */    private static boolean checkBridge(JsonObject c) {
        String itemId = c.get("item").getAsString();
        Item item = resolveItem(itemId);
        if (item == null) {
            SmartMaid.LOGGER.info("checkBridge: 未找到物品 " + itemId);
            return false;
        }
        boolean allowed = MaidActions.isBridgeBlock(new ItemStack(item));
        boolean want = !c.has("allowed") || c.get("allowed").getAsBoolean();
        SmartMaid.LOGGER.info("checkBridge " + itemId + " allowed=" + allowed + " 期望=" + want);
        return allowed == want;
    }

    /** 数据包 tag 断言：验证 tag 文件真的生效（如 #smartmaid:never_target 是否含末影人） */
    private static boolean checkTag(JsonObject c) {
        Identifier tagId = Identifier.tryParse(c.get("tag").getAsString());
        Identifier entityId = Identifier.tryParse(c.get("entity").getAsString());
        if (tagId == null || entityId == null) {
            SmartMaid.LOGGER.info("checkTag: tag/entity id 非法");
            return false;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(entityId).map(Holder::value).orElse(null);
        if (type == null) {
            SmartMaid.LOGGER.info("checkTag: 未找到实体 " + entityId);
            return false;
        }
        TagKey<EntityType<?>> tag = TagKey.create(Registries.ENTITY_TYPE, tagId);
        boolean inTag = type.builtInRegistryHolder().is(tag);
        boolean want = !c.has("contains") || c.get("contains").getAsBoolean();
        SmartMaid.LOGGER.info("checkTag " + entityId + " in " + tagId + " = " + inTag + " 期望=" + want);
        return inTag == want;
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
                SmartMaid.LOGGER.info("AutoTest 断言失败: " + e.getKey()
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
