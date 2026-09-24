package com.oyxdsg.smartmaid.entity.ai.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 结构扫描（Atomic Command Protocol §5.2）：find 算法的实现。
 *
 * <p>以女仆为中心，从 {@code range} 起逐轮扩大（{@code expand}）直到 {@code max_range}：
 * 收集 core 方块 → BFS 连通组件（core ∪ support）→ 结构判定 → 产物过滤（produce 谓词）
 * → 物种对齐（produce 指定 wood 家族时要求树叶同物种）→ 返回第一个命中组件。
 * 性能由 {@code scanBlockLimit} 与 {@code max_range} 双重护栏。</p>
 */
public final class StructureScan {

    /** find 默认参数（Atomic Command Protocol §九 护栏） */
    public static final int DEFAULT_RANGE = 12;
    public static final int DEFAULT_MAX_RANGE = 32;
    public static final int DEFAULT_EXPAND = 4;
    /** 垂直扫描范围（格）：树通常向上生长 */
    private static final int Y_DOWN = 6;
    private static final int Y_UP = 24;

    private StructureScan() {
    }

    /** find 查询（查询原子，无副作用） */
    public static JsonObject find(SmartMaidEntity maid, JsonObject params) {
        String structureId = str(params, "structure", "tree");
        StructureType st = StructureRegistry.get(structureId);
        if (st == null) {
            return fail("未知结构类型: " + structureId);
        }
        String produce = str(params, "produce", null);
        ProduceFilter.Compiled filter = ProduceFilter.compile(produce);
        int range = Math.max(1, intParam(params, "range", DEFAULT_RANGE));
        int maxRange = Math.max(range, intParam(params, "max_range", DEFAULT_MAX_RANGE));
        int expand = Math.max(1, intParam(params, "expand", DEFAULT_EXPAND));

        Level level = maid.level();
        BlockPos center = maid.blockPosition();
        int limit = st.scanBlockLimit();
        int scanned = 0;

        for (int r = range; r <= maxRange; r += expand) {
            if (MaidDebug.verbose()) {
                MaidDebug.log("Script find: structure=" + structureId + " produce=" + produce
                        + " 范围 " + r + "/" + maxRange + " 已扫 " + scanned);
            }
            BlockPos a = center.offset(-r, -Y_DOWN, -r);
            BlockPos b = center.offset(r, Y_UP, r);
            Set<Long> seen = new HashSet<>();
            for (BlockPos pos : BlockPos.betweenClosed(a, b)) {
                if (++scanned > limit) {
                    break;
                }
                BlockState s = level.getBlockState(pos);
                if (!st.coreBlock().test(s) || seen.contains(pos.asLong())) {
                    continue;
                }
                StructureComponent comp = collectComponent(level, pos, st, seen, st.scanBlockLimit());
                if (comp == null || !st.isStructure(comp)) {
                    continue;
                }
                if (!matchesProduce(level, comp, filter.matcher())) {
                    if (MaidDebug.verbose()) {
                        MaidDebug.log("Script find 过滤: structure=" + structureId + " produce=" + produce
                                + " 组件 core=" + comp.coreCount() + " 未命中产物");
                    }
                    continue;
                }
                if (filter.species() != null && !hasSpeciesLeaves(level, comp, filter.species())) {
                    if (MaidDebug.verbose()) {
                        MaidDebug.log("Script find 物种对齐失败: structure=" + structureId
                                + " produce=" + produce + " 组件无该物种树叶");
                    }
                    continue;
                }
                JsonObject hit = buildResult(maid, level, st, comp, filter.matcher());
                MaidDebug.log("Script find 命中: " + structureId + " pos=" + hit.get("pos")
                        + " remaining=" + comp.coreCount() + " matches=" + hit.get("produce_matches"));
                return hit;
            }
            if (scanned > limit) {
                break;
            }
        }
        MaidDebug.log("Script find 未命中: structure=" + structureId + " produce=" + produce
                + " searched=" + maxRange + " scanned=" + scanned);
        JsonObject out = new JsonObject();
        out.addProperty("found", false);
        out.addProperty("structure", structureId);
        out.addProperty("searched", maxRange);
        return out;
    }

    /** 沿 core ∪ support 做 6 邻域 BFS 连通组件；超限返回 null（放弃该组件） */
    private static StructureComponent collectComponent(Level level, BlockPos seed, StructureType st,
                                                       Set<Long> seen, int limit) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> cores = new ArrayList<>();
        List<BlockPos> supports = new ArrayList<>();
        queue.add(seed);
        seen.add(seed.asLong());
        int n = 0;
        while (!queue.isEmpty()) {
            if (n >= limit) {
                return null;
            }
            BlockPos p = queue.poll();
            n++;
            BlockState s = level.getBlockState(p);
            if (st.coreBlock().test(s)) {
                cores.add(p);
            } else if (st.supportBlock().test(s)) {
                supports.add(p);
            }
            for (Direction d : Direction.values()) {
                BlockPos q = p.relative(d);
                if (!seen.add(q.asLong())) {
                    continue;
                }
                BlockState qs = level.getBlockState(q);
                if (st.coreBlock().test(qs) || st.supportBlock().test(qs)) {
                    queue.add(q);
                }
            }
        }
        return new StructureComponent(cores, supports);
    }

    /** 组件内是否存在命中 produce 谓词的 core */
    private static boolean matchesProduce(Level level, StructureComponent comp, Predicate<BlockState> matcher) {
        for (BlockPos p : comp.cores()) {
            if (matcher.test(level.getBlockState(p))) {
                return true;
            }
        }
        return false;
    }

    /** 物种对齐：组件内 support（树叶）包含该物种对应树叶 */
    private static boolean hasSpeciesLeaves(Level level, StructureComponent comp, String species) {
        Identifier leavesId = TreeStructure.leavesIdOf(species);
        if (leavesId == null) {
            return true; // 该物种无树叶概念 → 不额外要求
        }
        for (BlockPos p : comp.supports()) {
            Identifier bid = BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock());
            if (bid != null && bid.equals(leavesId)) {
                return true;
            }
        }
        return false;
    }

    /** 命中结果：pos=离女仆最近的 core；produce_matches=组件内命中谓词的方块类型去重；remaining=组件 core 数 */
    private static JsonObject buildResult(SmartMaidEntity maid, Level level, StructureType st,
                                          StructureComponent comp, Predicate<BlockState> matcher) {
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        BlockPos maidPos = maid.blockPosition();
        for (BlockPos p : comp.cores()) {
            double d = p.distSqr(maidPos);
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("found", true);
        out.addProperty("structure", st.id());
        if (nearest != null) {
            out.add("pos", posArray(nearest));
        }
        Set<String> types = new LinkedHashSet<>();
        for (BlockPos p : comp.cores()) {
            BlockState s = level.getBlockState(p);
            if (matcher.test(s)) {
                Identifier bid = BuiltInRegistries.BLOCK.getKey(s.getBlock());
                if (bid != null) {
                    types.add(bid.toString());
                }
            }
        }
        JsonArray matches = new JsonArray();
        for (String t : types) {
            matches.add(t);
        }
        out.add("produce_matches", matches);
        out.addProperty("remaining", comp.coreCount());
        return out;
    }

    public static JsonArray posArray(BlockPos pos) {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }

    public static BlockPos posFromArray(SmartMaidEntity maid, JsonElement elem) {
        if (elem == null || !elem.isJsonArray() || elem.getAsJsonArray().size() < 3) {
            return null;
        }
        JsonArray arr = elem.getAsJsonArray();
        Integer x = coord(maid.blockPosition().getX(), arr.get(0));
        Integer y = coord(maid.blockPosition().getY(), arr.get(1));
        Integer z = coord(maid.blockPosition().getZ(), arr.get(2));
        return (x == null || y == null || z == null) ? null : new BlockPos(x, y, z);
    }

    private static Integer coord(int base, com.google.gson.JsonElement e) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsInt();
        }
        try {
            String s = e.getAsString();
            if (s.startsWith("~")) {
                String rest = s.substring(1).trim();
                return rest.isEmpty() ? base : base + Integer.parseInt(rest);
            }
            return Integer.parseInt(s);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    private static int intParam(JsonObject o, String key, int def) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber()
                ? o.get(key).getAsInt() : def;
    }

    private static JsonObject fail(String reason) {
        JsonObject out = new JsonObject();
        out.addProperty("found", false);
        out.addProperty("error", reason);
        return out;
    }
}
