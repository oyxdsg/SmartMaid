package com.oyxdsg.smartmaid.entity.ai.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidAIBridge;
import com.oyxdsg.smartmaid.entity.ai.structure.StructureScan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.function.Predicate;

/**
 * 查询原子（Atomic Command Protocol §四 B）：inventory / block_at / find / find_entity /
 * find_item / distance。全部**无副作用**，返回结构化 result 供变量绑定与条件判断。
 */
public final class ScriptQueries {

    /** 本层可识别的查询指令 */
    public static boolean isQuery(String cmd) {
        return switch (cmd) {
            case "inventory", "block_at", "find", "find_entity", "find_item", "distance" -> true;
            default -> false;
        };
    }

    private ScriptQueries() {
    }

    /** 执行查询；失败时 err 非空且返回 null */
    public static JsonObject query(SmartMaidEntity maid, String cmd, JsonObject params, StringBuilder err) {
        return switch (cmd) {
            case "inventory" -> inventory(maid, params);
            case "block_at" -> blockAt(maid, params, err);
            case "find" -> StructureScan.find(maid, params);
            case "find_entity" -> findEntity(maid, params);
            case "find_item" -> findItem(maid, params);
            case "distance" -> distance(maid, params, err);
            default -> {
                err.append("未知查询: ").append(cmd);
                yield null;
            }
        };
    }

    // ---------- 背包 ----------

    /** inventory：item（id / #tag / 缺省=全列）→ {has, count, slot, items[]} */
    private static JsonObject inventory(SmartMaidEntity maid, JsonObject params) {
        String itemSpec = params.has("item") ? params.get("item").getAsString() : null;
        Predicate<ItemStack> filter = itemFilter(itemSpec);
        SimpleContainer inv = maid.getMaidInventory();
        int total = 0;
        int firstSlot = -1;
        JsonArray items = new JsonArray();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !filter.test(stack)) {
                continue;
            }
            int count = stack.getCount();
            total += count;
            if (firstSlot < 0) {
                firstSlot = i;
            }
            if (itemSpec == null || itemSpec.startsWith("#")) {
                JsonObject s = new JsonObject();
                s.addProperty("slot", i);
                s.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                s.addProperty("count", count);
                items.add(s);
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("has", total > 0);
        out.addProperty("count", total);
        out.addProperty("slot", firstSlot);
        out.add("items", items);
        return out;
    }

    /** item 参数 → 谓词：null/#tag → 全匹配；#tag → tag 匹配；id → 精确匹配 */
    static Predicate<ItemStack> itemFilter(String itemSpec) {
        if (itemSpec == null || itemSpec.isEmpty()) {
            return s -> !s.isEmpty();
        }
        if (itemSpec.startsWith("#")) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM,
                    Identifier.tryParse(itemSpec.substring(1)));
            return s -> !s.isEmpty() && s.is(tag);
        }
        Item item = BuiltInRegistries.ITEM.get(Identifier.tryParse(itemSpec))
                .map(Holder::value).orElse(null);
        return s -> !s.isEmpty() && item != null && s.is(item);
    }

    // ---------- 方块 ----------

    /** block_at：pos → {block, hardness, breakable, tool_required} */
    private static JsonObject blockAt(SmartMaidEntity maid, JsonObject params, StringBuilder err) {
        BlockPos pos = pos(maid, params, err);
        if (pos == null) {
            return null;
        }
        Level level = maid.level();
        BlockState state = level.getBlockState(pos);
        float hardness = state.getDestroySpeed(level, pos);
        boolean isAir = state.isAir();
        JsonObject out = new JsonObject();
        out.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        out.addProperty("hardness", hardness);
        out.addProperty("breakable", !isAir && hardness >= 0.0F);
        out.addProperty("tool_required", !isAir && state.requiresCorrectToolForDrops());
        return out;
    }

    // ---------- 结构 / 实体 / 距离 ----------

    /** find_entity：type? / range → {found, uuid, type, pos, dist} */
    private static JsonObject findEntity(SmartMaidEntity maid, JsonObject params) {
        final EntityType<?> type = params.has("type")
                ? BuiltInRegistries.ENTITY_TYPE.get(Identifier.tryParse(params.get("type").getAsString()))
                .map(Holder::value).orElse(null)
                : null;
        int range = intParam(params, "range", 16);
        LivingEntity nearest = MaidActions.findEntities(maid, LivingEntity.class, range,
                        e -> e.isAlive() && e != maid && e != maid.getOwner()
                                && (type == null || e.getType() == type))
                .stream()
                .min(Comparator.comparingDouble(e -> maid.distanceToSqr(e)))
                .orElse(null);
        JsonObject out = new JsonObject();
        if (nearest == null) {
            out.addProperty("found", false);
            return out;
        }
        out.addProperty("found", true);
        out.addProperty("uuid", nearest.getUUID().toString());
        out.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(nearest.getType()).toString());
        out.add("pos", StructureScan.posArray(nearest.blockPosition()));
        out.addProperty("dist", Math.round(maid.distanceTo(nearest) * 10.0D) / 10.0D);
        return out;
    }

    /** find_item：item? / range → {found, pos, count} */
    private static JsonObject findItem(SmartMaidEntity maid, JsonObject params) {
        String itemSpec = params.has("item") ? params.get("item").getAsString() : null;
        int range = intParam(params, "range", 8);
        ItemEntity item = MaidActions.findNearestItem(maid, range, itemFilter(itemSpec));
        JsonObject out = new JsonObject();
        if (item == null) {
            out.addProperty("found", false);
            return out;
        }
        out.addProperty("found", true);
        out.add("pos", StructureScan.posArray(item.blockPosition()));
        out.addProperty("count", item.getItem().getCount());
        return out;
    }

    /** distance：pos → {dist}（水平距离） */
    private static JsonObject distance(SmartMaidEntity maid, JsonObject params, StringBuilder err) {
        BlockPos pos = pos(maid, params, err);
        if (pos == null) {
            return null;
        }
        double dx = maid.getX() - (pos.getX() + 0.5D);
        double dz = maid.getZ() - (pos.getZ() + 0.5D);
        JsonObject out = new JsonObject();
        out.addProperty("dist", Math.round(Math.sqrt(dx * dx + dz * dz) * 10.0D) / 10.0D);
        return out;
    }

    // ---------- 工具 ----------

    private static BlockPos pos(SmartMaidEntity maid, JsonObject params, StringBuilder err) {
        return MaidAIBridge.parsePos(maid, params, "pos", err);
    }

    private static int intParam(JsonObject o, String key, int def) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber()
                ? o.get(key).getAsInt() : def;
    }
}
