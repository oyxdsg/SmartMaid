package com.oyxdsg.smartmaid.entity.ai.structure;

import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidBlockBreaker;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidAITask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 收割任务（Atomic Command Protocol §5.4）：对某结构（树/矿脉…），按 produce 谓词过滤，
 * 只破坏命中谓词的 core 方块（如只挖橡树原木不误伤金合欢；只挖铁矿石），到达 count 或
 * 该结构产物耗尽结束。BFS 从种子位置沿该 StructureType 的 core ∪ support 扩展收集目标。
 */
public class HarvestTask extends MaidAITask {

    /** BFS 收集目标方块的硬上限（防超大组件一次扫太多，性能护栏） */
    private static final int SCAN_TARGET_LIMIT = 2000;

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
        // 取下一个可挖目标（已空/不再命中则跳过）
        while (!this.pending.isEmpty()) {
            BlockPos p = this.pending.poll();
            BlockState s = maid.level().getBlockState(p);
            if (s.isAir() || !this.matcher.test(s)) {
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

    /** BFS 从 seed 沿 core ∪ support 扩展，收集命中 matcher 的 core */
    private void scan(SmartMaidEntity maid) {
        Level level = maid.level();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(this.seed);
        this.visited.add(this.seed.asLong());
        while (!queue.isEmpty() && this.remaining < SCAN_TARGET_LIMIT) {
            BlockPos p = queue.poll();
            BlockState s = level.getBlockState(p);
            if (this.matcher.test(s)) {
                this.pending.add(p);
                this.remaining++;
            }
            for (Direction d : Direction.values()) {
                BlockPos q = p.relative(d);
                if (!this.visited.add(q.asLong())) {
                    continue;
                }
                BlockState qs = level.getBlockState(q);
                if (this.structure.coreBlock().test(qs) || this.structure.supportBlock().test(qs)) {
                    queue.add(q);
                }
            }
        }
        MaidDebug.log("Harvest scan: 目标 " + this.remaining + " 个");
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
    public JsonObject resultJson() {
        JsonObject out = new JsonObject();
        out.addProperty("collected", this.collected);
        out.addProperty("remaining", Math.max(0, this.remaining));
        out.add("pos", StructureScan.posArray(this.seed));
        return out;
    }
}
