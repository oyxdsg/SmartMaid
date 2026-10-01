package com.oyxdsg.smartmaid.entity.ai.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidBlockBreaker;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidAITask;
import com.oyxdsg.smartmaid.entity.ai.maidtask.Resumable;
import com.oyxdsg.smartmaid.entity.ai.maidtask.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 收割任务（Atomic Command Protocol §5.4）：对某结构（树/矿脉…），把种子位置所在的那一团
 * <b>相连资源</b>采完（树 = 一棵完整的树、矿 = 一个完整的矿簇），到达 count 或该结构耗尽结束。
 * BFS 从种子位置沿 {@code core}（资源本身）扩展 —— 判据是"相连的资源"，<b>不看树种/矿种</b>：
 * 深色橡木、白桦、橡木同样是树。{@code produce} 只用于 {@code find} 阶段挑哪一棵树。
 */
public class HarvestTask extends MaidAITask implements Resumable {

    /** BFS 收集目标方块的硬上限（防超大组件一次扫太多，性能护栏） */
    private static final int SCAN_TARGET_LIMIT = 2000;

    /** 起点容错半径：seed 已被挖掉/不匹配时，在这个半径内找最近的资源方块当起点 */
    private static final int SEED_RECOVER_RADIUS = 2;

    private final BlockPos seed;
    private final Predicate<BlockState> matcher;
    private final int count; // >0 目标数量；<=0 不限（直到产物耗尽）
    private final StructureType structure; // BFS 扩展谓词（core ∪ support）

    private final MaidBlockBreaker breaker = new MaidBlockBreaker();
    private final ArrayDeque<BlockPos> pending = new ArrayDeque<>();
    private final Set<Long> visited = new HashSet<>();
    /** 被遮挡重试次数：被挡放回队列末尾重试，超限放弃（防死循环） */
    private final Map<BlockPos, Integer> retryCount = new HashMap<>();
    private static final int MAX_RETRY = 4;
    private int collected;
    private int remaining;
    private boolean scanned;
    private boolean done;
    private boolean lastOk = true;

    public HarvestTask(BlockPos seed, Predicate<BlockState> matcher, int count, StructureType structure) {
        super("harvest");
        this.seed = seed;
        this.matcher = matcher;
        this.count = count;
        this.structure = structure;
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return this.seed != null && this.matcher != null;
    }

    @Override
    public void start(SmartMaidEntity maid) {
        this.collected = 0;
        this.remaining = 0;
        this.scanned = false;
        this.done = false;
        this.lastOk = true;
        this.retryCount.clear();
        MaidDebug.log("Harvest start seed=" + seed + " count=" + count);
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        if (this.done) {
            return;
        }
        if (this.breaker.isActive()) {
            BlockPos currentTarget = this.breaker.target();
            this.lastOk = this.breaker.tick(maid);
            if (this.lastOk) {
                this.collected++;
                if (this.remaining > 0) {
                    this.remaining--;
                }
                MaidDebug.log("Harvest 收割 " + this.collected + " 块（目标 "
                        + (this.count > 0 ? this.count : "产物耗尽") + "）");
            } else if (!this.breaker.isActive() && currentTarget != null
                    && !maid.level().getBlockState(currentTarget).isAir()) {
                // 目标被遮挡/不可达 → 放回队列末尾（从外到内逐步收割，对齐玩家砍树行为），
                // 等外围方块挖掉后它变为可达再收割；超限重试则放弃（防死循环）
                int n = this.retryCount.merge(currentTarget, 1, Integer::sum);
                if (n >= MAX_RETRY) {
                    if (MaidDebug.verbose()) {
                        MaidDebug.log("Harvest 目标多次被挡，放弃 " + currentTarget);
                    }
                } else {
                    if (MaidDebug.verbose()) {
                        MaidDebug.log("Harvest 目标被挡，放回队列 " + currentTarget);
                    }
                    this.pending.addLast(currentTarget);
                }
            }
            return;
        }
        if (!this.scanned) {
            scan(maid);
            this.scanned = true;
            if (this.pending.isEmpty()) {
                MaidDebug.log("Harvest 结束（无目标可收）collected=" + this.collected);
                this.done = true;
                return;
            }
        }
        if (this.count > 0 && this.collected >= this.count) {
            MaidDebug.log("Harvest 结束（达成目标）collected=" + this.collected
                    + "/" + this.count);
            this.done = true;
            return;
        }
        // 取下一个可挖目标：目标池在 scan() 里已经确定为"这棵相连的资源"，这里只跳过已空的
        // （**不能再用产物谓词过滤** —— 否则深色橡木这类"同为 tree 的 core、但不匹配 produce"的
        //  方块会被全部跳过，表现为"扫到 47 个目标却 0 个可挖"）
        while (!this.pending.isEmpty()) {
            BlockPos p = this.pending.poll();
            BlockState s = maid.level().getBlockState(p);
            if (s.isAir()) {
                continue;
            }
            // 重试超限的目标直接放弃（多次被挡，玩家也挖不到）
            if (this.retryCount.getOrDefault(p, 0) >= MAX_RETRY) {
                continue;
            }
            if (this.breaker.begin(maid, p)) {
                return;
            }
            if (MaidDebug.verbose()) {
                MaidDebug.log("Harvest 跳过不可挖: " + p);
            }
        }
        MaidDebug.log("Harvest 结束（无剩余目标）collected=" + this.collected);
        this.done = true;
    }

    /**
     * BFS 从 seed 沿**相连的资源（core）**扩展，收集目标方块 —— 即"一棵完整的树 / 一个完整的矿簇"。
     *
     * <h2>判据分层（2026-09-29 定稿，前两版都错在这里）</h2>
     * <ul>
     *   <li><b>连通性 = core</b>（{@code #minecraft:logs} 这类"资源本身"）。木头就是木头：
     *       深色橡木、白桦、橡木都是 tree 的 core，<b>不能因为产物谓词写着 oaks 就把别的树种开除出"树"</b>。</li>
     *   <li><b>范围 = 相连的 core</b>：原木之间彼此相连、不同树只靠树叶相接，所以沿 core 扩展天然锁定
     *       <b>一棵树</b>（矿同理锁一个矿簇），既不会跨树、也不需要物种过滤。</li>
     *   <li><b>produce 不参与"哪些方块算目标"</b>：它只在 {@code find} 阶段决定"挑哪一棵树/哪个矿簇"。
     *       结构一旦选定，就把这棵相连的资源<b>整棵采完</b>。</li>
     * </ul>
     *
     * <p><b>教训（两版错误的记录）：</b>①沿 {@code core ∪ support} 扩展会把整片林子连成一个结构
     * （真机 {@code find remaining=1424}）；②沿 {@code matcher} 扩展等于用产物谓词当连通性判据，
     * 于是"附近有一棵深色橡木"会退化成"一个目标都没有"（真机
     * {@code Harvest seed ... 实际 minecraft:dark_oak_log → 目标 0 个}）。</p>
     *
     * <p><b>起点容错：</b>seed 可能已经不是资源方块——脚本常是 {@code find → move → harvest}，
     * move 用同一个坐标，而寻路降级直线会把挡路的目标本身挖掉。此时在
     * {@link #SEED_RECOVER_RADIUS} 半径内找最近的 core 当起点。</p>
     */
    private void scan(SmartMaidEntity maid) {
        Level level = maid.level();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(this.seed);
        this.visited.add(this.seed.asLong());
        if (!this.structure.coreBlock().test(level.getBlockState(this.seed))) {
            BlockPos alt = nearestCore(level, this.seed, SEED_RECOVER_RADIUS);
            MaidDebug.log("Harvest seed " + this.seed + " 已非资源（实际 "
                    + BuiltInRegistries.BLOCK.getKey(level.getBlockState(this.seed).getBlock())
                    + "），起点容错 -> " + alt);
            if (alt != null) {
                queue.add(alt);
                this.visited.add(alt.asLong());
            }
        }
        while (!queue.isEmpty() && this.remaining < SCAN_TARGET_LIMIT) {
            BlockPos p = queue.poll();
            BlockState s = level.getBlockState(p);
            if (this.structure.coreBlock().test(s)) {
                this.pending.add(p);
                this.remaining++;
            }
            for (Direction d : Direction.values()) {
                BlockPos q = p.relative(d);
                if (!this.visited.add(q.asLong())) {
                    continue;
                }
                // 只沿资源本身（core）扩展：一棵树 = 相连的原木（不分树种）；support（树叶）会串遍整片林子
                if (this.structure.coreBlock().test(level.getBlockState(q))) {
                    queue.add(q);
                }
            }
        }
        MaidDebug.log("Harvest scan: 目标 " + this.remaining + " 个（seed " + this.seed
                + " 实际 " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(this.seed).getBlock())
                + "，产物命中=" + this.matcher.test(level.getBlockState(this.seed)) + "）");
    }

    /** 在以 center 为中心、半径 radius 的立方体内找最近的 core 方块（不含 center 自身） */
    private BlockPos nearestCore(Level level, BlockPos center, int radius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(
                center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            if (p.equals(center) || !this.structure.coreBlock().test(level.getBlockState(p))) {
                continue;
            }
            double d = p.distSqr(center);
            if (d < bestDist) {
                bestDist = d;
                best = p.immutable();  // betweenClosed 返回可变游标，必须拷贝
            }
        }
        return best;
    }

    // ---------- Resumable（Q7） ----------

    @Override
    public JsonObject saveState() {
        JsonObject o = new JsonObject();
        o.addProperty("version", stateVersion());
        o.addProperty("collected", this.collected);
        o.addProperty("remaining", this.remaining);
        o.addProperty("scanned", this.scanned);
        o.addProperty("done", this.done);
        o.addProperty("lastOk", this.lastOk);
        o.add("pending", TaskState.posList(this.pending));
        JsonArray visitedArr = new JsonArray();
        for (Long l : this.visited) {
            visitedArr.add(l);
        }
        o.add("visited", visitedArr);
        JsonArray retryArr = new JsonArray();
        for (Map.Entry<BlockPos, Integer> e : this.retryCount.entrySet()) {
            JsonObject r = new JsonObject();
            r.add("pos", TaskState.pos(e.getKey()));
            r.addProperty("n", e.getValue());
            retryArr.add(r);
        }
        o.add("retry", retryArr);
        return o;
    }

    @Override
    public void restoreState(JsonObject s) {
        if (s == null) {
            return;
        }
        this.collected = s.has("collected") ? s.get("collected").getAsInt() : 0;
        this.remaining = s.has("remaining") ? s.get("remaining").getAsInt() : 0;
        this.scanned = s.has("scanned") && s.get("scanned").getAsBoolean();
        this.done = s.has("done") && s.get("done").getAsBoolean();
        this.lastOk = !s.has("lastOk") || s.get("lastOk").getAsBoolean();
        this.pending.clear();
        this.pending.addAll(TaskState.readPosList(s.get("pending")));
        this.visited.clear();
        if (s.has("visited") && s.get("visited").isJsonArray()) {
            for (JsonElement e : s.getAsJsonArray("visited")) {
                this.visited.add(e.getAsLong());
            }
        }
        this.retryCount.clear();
        if (s.has("retry") && s.get("retry").isJsonArray()) {
            for (JsonElement e : s.getAsJsonArray("retry")) {
                if (e.isJsonObject()) {
                    JsonObject r = e.getAsJsonObject();
                    BlockPos p = TaskState.readPos(r.get("pos"));
                    if (p != null) {
                        this.retryCount.put(p, r.has("n") ? r.get("n").getAsInt() : 0);
                    }
                }
            }
        }
    }

    @Override
    public int stateVersion() {
        return 1;
    }

    @Override
    public boolean validateState(SmartMaidEntity maid, JsonObject s) {
        return this.seed != null && this.matcher != null && maid != null;
    }

    @Override
    public boolean isDone() {
        return this.done;
    }

    @Override
    public String result() {
        return this.done ? "已收割 " + this.collected + " 个" : "收割中";
    }

    @Override
    public void forceStop(SmartMaidEntity maid) {
        this.breaker.abort(maid);
        this.done = true;
    }

    @Override
    public String progressToken() {
        return "collected=" + this.collected;
    }

    @Override
    public JsonObject resultJson() {
        JsonObject out = new JsonObject();
        out.addProperty("collected", this.collected);
        out.addProperty("remaining", Math.max(0, this.remaining));
        out.add("pos", StructureScan.posArray(this.seed));
        // 未收到任何目标时说明原因，便于 AI 决策（换位置重试 / 放弃）
        if (this.scanned && this.collected == 0) {
            out.addProperty("reason", "no_target");
        }
        return out;
    }
}
