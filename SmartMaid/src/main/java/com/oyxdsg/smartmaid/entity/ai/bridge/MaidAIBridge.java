package com.oyxdsg.smartmaid.entity.ai.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.craft.CraftExecutor;
import com.oyxdsg.smartmaid.entity.ai.maidtask.AttackTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.BuildTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.ChestOpenTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.CollectTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.CraftTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.EatTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.FarmTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.FeedTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.GuardTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidAITask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidTaskManager;
import com.oyxdsg.smartmaid.entity.ai.maidtask.MineTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.MoveToTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.OneShotTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.SmeltTask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.TransferTask;
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionBlockUtil;
import com.oyxdsg.smartmaid.entity.ai.script.ScriptTask;
import com.oyxdsg.smartmaid.entity.ai.slot.ItemSlot;
import com.oyxdsg.smartmaid.entity.ai.slot.Slots;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 指令桥接层（M4）：把外部 AI 的统一 JSON 指令解析成女仆可执行任务，并返回协议回执。
 *
 * <p>请求格式（对齐 DESIGN_AI_INTERFACE 协议）：</p>
 * <pre>{@code
 * {"id":"cmd-1","cmd":"mine","params":{"pos":[x,y,z],"range":4,"count":8},
 *  "cancel_previous":true,"persist":false}
 * }</pre>
 *
 * <p>与 AI 通道解耦：任何通道（桌宠 WebSocket / /maidai 指令 / 未来 LLM）都调用
 * {@link #execute(SmartMaidEntity, String)}，本类负责统一解析、参数校验、任务构造、
 * 派发到 {@link MaidTaskManager} 并返回 {@link MaidCommandResult} 回执。</p>
 *
 * <p>指令集与 {@code /maidtasks} 完全一致（attack/guard/feed/mine/farm/build/collect/craft/
 * smelt/transfer/chestopen/chestput/chesttake/move/look/break/place/use/equip/store/drop/
 * pickup/sit/stop/cancel/status）。</p>
 */
public final class MaidAIBridge {

    private MaidAIBridge() {
    }

    /**
     * 执行一条 JSON 指令。
     *
     * @return 回执（从未返回 null；JSON 非法/参数错误/前置不满足均返回失败回执）
     */
    public static MaidCommandResult execute(SmartMaidEntity maid, String json) {
        JsonObject req;
        try {
            req = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            return MaidCommandResult.fail("?", "JSON 解析失败: " + e.getMessage());
        }
        String id = req.has("id") ? req.get("id").getAsString() : "cmd";
        String cmd = req.has("cmd") ? req.get("cmd").getAsString() : "";
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();
        boolean cancelPrevious = !req.has("cancel_previous") || req.get("cancel_previous").getAsBoolean();
        boolean persist = req.has("persist") && req.get("persist").getAsBoolean();

        MaidTaskManager manager = maid.getMaidTaskManager();

        // 管理指令
        switch (cmd) {
            case "cancel" -> {
                manager.cancel();
                JsonObject result = new JsonObject();
                result.addProperty("task", "none");
                return MaidCommandResult.ok(id, "cancelled", "cancelled", result);
            }
            case "status" -> {
                MaidAITask current = manager.currentTask();
                JsonObject result = new JsonObject();
                result.addProperty("task", current == null ? null : current.taskId());
                result.addProperty("ai_busy", manager.isAiBusy());
                return MaidCommandResult.ok(id, "done", "queried", result);
            }
            case "craft_check" -> {
                // 干跑查询（不消耗材料）：供桌宠在自动合成前判断材料是否齐备、
                // 并拿到「缺什么、缺多少」的明细回报给用户/大 AI。
                StringBuilder checkErr = new StringBuilder();
                Item item = resolveItem(maid, params, checkErr);
                if (item == null) {
                    return MaidCommandResult.fail(id,
                            checkErr.length() > 0 ? checkErr.toString() : "无效物品");
                }
                JsonObject report = CraftExecutor.check(
                        maid, new ItemStack(item), intParam(params, "count", 1));
                return MaidCommandResult.ok(id, "done", "checked", report);
            }
            case "script" -> {
                // 原子层指令协议：AI 一次性规划的多步指令流，模组侧 ScriptTask 顺序执行。
                if (!params.has("steps") || !params.get("steps").isJsonArray()
                        || params.getAsJsonArray("steps").size() == 0) {
                    return MaidCommandResult.fail(id, "script 缺少非空 steps 数组");
                }
                if (!cancelPrevious && manager.isAiBusy()) {
                    return MaidCommandResult.fail(id, "女仆已有任务运行中（cancel_previous=false）");
                }
                boolean started = manager.setTask(new ScriptTask(id, params));
                if (!started) {
                    return MaidCommandResult.fail(id, "脚本前置条件不满足");
                }
                JsonObject scriptOk = new JsonObject();
                scriptOk.addProperty("task", "script");
                scriptOk.addProperty("note", "脚本已下发");
                return MaidCommandResult.ok(id, "running", "accepted", scriptOk);
            }
            default -> {
            }
        }

        // 任务指令
        StringBuilder err = new StringBuilder();
        MaidAITask task = buildTask(maid, cmd, params, err);
        if (task == null) {
            return MaidCommandResult.fail(id, err.length() > 0 ? err.toString() : "未知指令: " + cmd);
        }
        if (!cancelPrevious && manager.isAiBusy()) {
            return MaidCommandResult.fail(id, "女仆已有任务运行中（cancel_previous=false）");
        }
        boolean started = manager.setTask(task);
        if (persist) {
            MaidTaskConfig.save(maid, cmd, params);
        }
        if (!started) {
            return MaidCommandResult.fail(id, "任务前置条件不满足: " + task.taskId());
        }
        JsonObject result = new JsonObject();
        result.addProperty("task", task.taskId());
        result.addProperty("note", "任务已下发");
        return MaidCommandResult.ok(id, "running", "accepted", result);
    }

    // ---------- 指令 → 任务构造 ----------

    /** 供 {@link ScriptTask} 复用：把脚本 step 的指令参数构造成子任务（含集成任务/基础任务）。 */
    public static MaidAITask buildTask(SmartMaidEntity maid, String cmd, JsonObject params, StringBuilder err) {
        return switch (cmd) {
            case "attack" -> {
                EntityType<?> targetType = resolveEntityType(params, err);
                if (err.length() > 0) {
                    yield null;
                }
                yield new AttackTask(intParam(params, "range", 12), targetType);
            }
            case "guard" -> new GuardTask(intParam(params, "range", 16), boolParam(params, "defensive", false));
            case "feed" -> new FeedTask();
            case "eat" -> eatTask(maid, params, err);
            case "mine" -> {
                // 坐标可省：不给 pos 时以女仆当前脚下为中心自动探测周围矿物
                // （range 默认 12 覆盖水平整片矿脉，垂直由 MineTask 内部 8 格；
                // 给了 pos 则按指定范围）
                BlockPos pos = parsePos(maid, params, "pos", err);
                if (pos == null) {
                    err.setLength(0);   // pos 可选，缺省不算错误
                    pos = maid.blockPosition();
                }
                yield new MineTask(pos, intParam(params, "range", 12), intParam(params, "count", 8));
            }
            case "farm" -> {
                BlockPos pos = parsePos(maid, params, "pos", err);
                if (pos == null) {
                    yield null;
                }
                yield new FarmTask(pos, intParam(params, "range", 4));
            }
            case "build" -> {
                BlockPos pos = parsePos(maid, params, "pos", err);
                if (pos == null) {
                    yield null;
                }
                if (params.has("blocks")) {
                    yield new BuildTask(parseBlocks(maid, params.get("blocks"), err));
                }
                yield new BuildTask(stackUp(pos, intParam(params, "height", 1)));
            }
            case "collect" -> new CollectTask(intParam(params, "range", 8));
            case "craft" -> craftTask(maid, params, err);
            case "smelt" -> smeltTask(maid, params, err);
            case "transfer" -> transferTask(maid, params, err);
            case "chestopen" -> {
                BlockPos pos = parsePos(maid, params, "pos", err);
                if (pos == null) {
                    yield null;
                }
                yield new ChestOpenTask(pos);
            }
            case "chestput" -> chestPutTask(maid, params, err);
            case "chesttake" -> chestTakeTask(maid, params, err);
            case "move" -> {
                BlockPos pos = parsePos(maid, params, "pos", err);
                if (pos == null) {
                    yield null;
                }
                yield new MoveToTask(pos, 1.0D, 1.5D);
            }
            case "look" -> {
                BlockPos pos = parsePos(maid, params, "pos", err);
                if (pos == null) {
                    yield null;
                }
                yield new OneShotTask("look", "已面朝目标", () -> maid.getLookControl()
                        .setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D));
            }
            case "break" -> {
                BlockPos pos = parsePos(maid, params, "pos", err);
                if (pos == null) {
                    yield null;
                }
                yield new OneShotTask("break", "已挖方块", () -> MaidActions.breakBlock(maid, pos));
            }
            case "place" -> {
                BlockPos pos = parsePos(maid, params, "pos", err);
                if (pos == null) {
                    yield null;
                }
                yield new OneShotTask("place", "已放置方块",
                        () -> MaidActions.placeBlock(maid, pos.below(), Direction.UP));
            }
            case "use" -> {
                BlockPos pos = parsePos(maid, params, "pos", err);
                if (pos == null) {
                    yield null;
                }
                yield new OneShotTask("use", "已使用物品",
                        () -> MaidActions.useItemOn(maid, pos.below(), Direction.UP));
            }
            case "equip" -> {
                Item item = resolveItem(maid, params, err);
                if (item == null) {
                    yield null;
                }
                yield new OneShotTask("equip", "已装备到主手",
                        () -> MaidActions.equipFromBackpack(maid, stack -> stack.is(item)));
            }
            case "store" -> new OneShotTask("store", "已收纳主手物品", () -> {
                ItemStack hand = maid.getMainHandItem();
                if (!hand.isEmpty()) {
                    ItemStack left = MaidActions.storeToBackpack(maid, hand);
                    maid.setItemInHand(InteractionHand.MAIN_HAND, left);
                    maid.syncInventoryArmor();
                }
            });
            case "drop" -> new OneShotTask("drop", "已丢出", () -> {
                ItemStack hand = maid.getMainHandItem();
                if (!hand.isEmpty()) {
                    ItemStack drop = hand.split(Math.min(intParam(params, "count", 1), hand.getCount()));
                    // 丢到主人身边（丢在自己脚下会被立刻捡回）
                    MaidActions.dropToOwner(maid, drop);
                    maid.syncInventoryArmor();
                }
            });
            case "pickup" -> new CollectTask(intParam(params, "range", 4));
            case "sit" -> new OneShotTask("sit", "已切换坐姿",
                    // 坐/站统一切换：落地吸附 + 移动锁定都在 setOrderedToSit 内处理
                    () -> maid.setOrderedToSit(!maid.isOrderedToSit()));
            case "stop" -> new OneShotTask("stop", "已停止", () -> {
                maid.getNavigation().stop();
                maid.getMaidTaskManager().cancel();
            });
            default -> null;
        };
    }

    /** eat 指令：无 item 参数则吃背包里营养最高的食物。 */
    private static MaidAITask eatTask(SmartMaidEntity maid, JsonObject params, StringBuilder err) {
        if (!params.has("item")) {
            return new EatTask(null);
        }
        Item item = resolveItem(maid, params, err);
        return err.length() > 0 ? null : new EatTask(item);
    }

    private static MaidAITask craftTask(SmartMaidEntity maid, JsonObject params, StringBuilder err) {        Item item = resolveItem(maid, params, err);
        if (item == null) {
            return null;
        }
        return new CraftTask(new ItemStack(item), intParam(params, "count", 1));
    }

    private static MaidAITask smeltTask(SmartMaidEntity maid, JsonObject params, StringBuilder err) {
        Item item = resolveItem(maid, params, err);
        if (item == null) {
            return null;
        }
        return new SmeltTask(new ItemStack(item), intParam(params, "count", 1));
    }

    private static MaidAITask transferTask(SmartMaidEntity maid, JsonObject params, StringBuilder err) {
        String fromSpec = params.has("from") ? params.get("from").getAsString() : "";
        String toSpec = params.has("to") ? params.get("to").getAsString() : "";
        ItemSlot from = Slots.parse(fromSpec);
        ItemSlot to = Slots.parse(toSpec);
        if (from == null) {
            err.append("无效源槽位: ").append(fromSpec);
            return null;
        }
        if (to == null) {
            err.append("无效目标槽位: ").append(toSpec);
            return null;
        }
        return new TransferTask(from, to, intParam(params, "count", 64));
    }

    private static MaidAITask chestPutTask(SmartMaidEntity maid, JsonObject params, StringBuilder err) {
        BlockPos pos = maid.getOpenedContainer();
        if (pos == null || !PerceptionBlockUtil.isContainerAt(maid.level(), pos)) {
            err.append("女仆还没打开箱子（或箱子不在了），先 chestopen");
            return null;
        }
        return new TransferTask(Slots.mainhand(), Slots.container(pos, -1), intParam(params, "count", 64));
    }

    private static MaidAITask chestTakeTask(SmartMaidEntity maid, JsonObject params, StringBuilder err) {
        BlockPos pos = maid.getOpenedContainer();
        if (pos == null || !PerceptionBlockUtil.isContainerAt(maid.level(), pos)) {
            err.append("女仆还没打开箱子（或箱子不在了），先 chestopen");
            return null;
        }
        return new TransferTask(Slots.container(pos, intParam(params, "slot", -1)), Slots.mainhand(),
                intParam(params, "count", 1));
    }

    // ---------- 参数解析 ----------

    private static int intParam(JsonObject params, String key, int def) {
        return params.has(key) && params.get(key).isJsonPrimitive() && params.get(key).getAsJsonPrimitive().isNumber()
                ? params.get(key).getAsInt() : def;
    }

    private static boolean boolParam(JsonObject params, String key, boolean def) {
        return params.has(key) && params.get(key).isJsonPrimitive() && params.get(key).getAsJsonPrimitive().isBoolean()
                ? params.get(key).getAsBoolean() : def;
    }

    /** 解析坐标参数：[x,y,z]，元素可为数字或 ~ / ~5（以女仆位置为基准）。供查询原子/脚本复用。 */
    public static BlockPos parsePos(SmartMaidEntity maid, JsonObject params, String key, StringBuilder err) {
        if (!params.has(key) || !params.get(key).isJsonArray()) {
            err.append("缺少坐标参数: ").append(key).append("（格式 [x,y,z]）");
            return null;
        }
        JsonArray arr = params.getAsJsonArray(key);
        if (arr.size() < 3) {
            err.append("坐标参数需 3 个分量: ").append(key);
            return null;
        }
        Integer x = parseCoord(maid.blockPosition().getX(), arr.get(0));
        Integer y = parseCoord(maid.blockPosition().getY(), arr.get(1));
        Integer z = parseCoord(maid.blockPosition().getZ(), arr.get(2));
        if (x == null || y == null || z == null) {
            err.append("坐标分量无效: ").append(key);
            return null;
        }
        return new BlockPos(x, y, z);
    }

    private static Integer parseCoord(int base, JsonElement e) {
        String s;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsInt();
        }
        try {
            s = e.getAsString();
        } catch (Exception ex) {
            return null;
        }
        if (s.startsWith("~")) {
            String rest = s.substring(1).trim();
            if (rest.isEmpty()) {
                return base;
            }
            try {
                return base + Integer.parseInt(rest);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 解析可选的实体类型参数 target（如 pig / minecraft:pig）；未给返回 null，非法则写 err。 */
    private static EntityType<?> resolveEntityType(JsonObject params, StringBuilder err) {
        if (!params.has("target") || params.get("target").isJsonNull()) {
            return null;
        }
        String id = params.get("target").getAsString();
        Identifier identifier = Identifier.tryParse(id);
        EntityType<?> type = identifier == null ? null
                : BuiltInRegistries.ENTITY_TYPE.get(identifier).map(holder -> holder.value()).orElse(null);
        if (type == null) {
            err.append("未找到实体类型: ").append(id);
        }
        return type;
    }

    private static Item resolveItem(SmartMaidEntity maid, JsonObject params, StringBuilder err) {
        if (!params.has("item")) {
            err.append("缺少物品参数: item");
            return null;
        }
        String id = params.get("item").getAsString();
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) {
            err.append("无效物品 id: ").append(id);
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(identifier).map(holder -> holder.value()).orElse(null);
        if (item == null) {
            err.append("未找到物品: ").append(id);
        }
        return item;
    }

    /** 解析 blocks 列表（build 用）：[[x,y,z],...] */
    private static List<BlockPos> parseBlocks(SmartMaidEntity maid, JsonElement blocks, StringBuilder err) {
        List<BlockPos> list = new ArrayList<>();
        if (blocks.isJsonArray()) {
            for (JsonElement e : blocks.getAsJsonArray()) {
                if (e.isJsonArray() && e.getAsJsonArray().size() >= 3) {
                    JsonArray a = e.getAsJsonArray();
                    Integer x = parseCoord(maid.blockPosition().getX(), a.get(0));
                    Integer y = parseCoord(maid.blockPosition().getY(), a.get(1));
                    Integer z = parseCoord(maid.blockPosition().getZ(), a.get(2));
                    if (x != null && y != null && z != null) {
                        list.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        if (list.isEmpty()) {
            err.append("blocks 列表为空或格式无效");
        }
        return list;
    }

    /** 从 pos 向上堆叠 height 个方块（同 /maidtasks build） */
    private static List<BlockPos> stackUp(BlockPos pos, int height) {
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 0; i < Math.max(1, height); i++) {
            positions.add(pos.offset(0, i, 0));
        }
        return positions;
    }
}
