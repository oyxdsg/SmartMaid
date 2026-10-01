package com.oyxdsg.smartmaid.entity.ai.maidtask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.PauseReason;
import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.QueueKind;
import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.State;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * AI 任务调度器（M2；Q2 接入任务队列，Q5 战斗打断改为暂停保留，Q6 去重互斥）。
 *
 * <p>与现有 Goal 协作：任务激活时 {@link #isAiBusy()} 返回 true，使
 * {@code MaidFollowGoal} / {@code MaidCombatGoal} / 散步 Goal 让路（其 canUse 检查该标志）；
 * L0 安全层与跳跃执行器不受影响。</p>
 *
 * <p>战斗优先级高于任务：{@link SmartMaidEntity#isCombatActive()} 为真时当前项
 * <b>暂停（{@link PauseReason#COMBAT}）+ 保留任务对象</b>并回队首，战斗结束自动继续（§五.2 / §六.1）。
 * 战斗本身不进队列（保留 Goal 层优先级）。</p>
 *
 * <p><b>双队列</b>：{@link MaidTaskQueue} 管理长期/短期两个队列，调度为
 * "短期非空 → 只跑短期队首；短期清空 → 跑长期队首"。{@link #setTask} 保持旧的<b>即时抢占</b>语义
 * （默认 {@code /maidtasks}、桌宠 WS、{@code script} 全部走它）；只有 {@link #enqueueTask}
 * 才进入队列。运行时 {@link MaidAITask} 对象在 {@link #runtimeTasks} 里按 id 保留，
 * 供会话内打断"挂起不销毁"（§六.1）——{@link #startedIds} 记录已 start 过的项，
 * 恢复时<b>不重复 start</b>，直接接着 tick。</p>
 *
 * <p><b>去重互斥（Q6）</b>：{@link #enqueueTask} 按 {@link TaskMutex} 计算 {@code mutexKey}
 * 做精确判重（同键拒绝）、泛化软提示、60t 时间窗去重、来源优先级（主人 P2 压过桌宠 P3），
 * 并对 {@code guard} 做"取最大 range"合并（§8.8a）。</p>
 */
public class MaidTaskManager {

    /** 任务最长运行 tick（60 秒兜底，防任务卡死） */
    private static final int MAX_TASK_TICKS = 20 * 60;
    /** 时间窗去重：同 mutexKey 在 60 tick（3 秒）内重复下发只接受第一条（§8.4） */
    private static final long DEDUP_WINDOW_TICKS = 60L;
    /** 来源优先级：主人显式（P2） */
    public static final int PRIORITY_OWNER = 2;
    /** 来源优先级：桌宠事件线 / 脚本 / AutoTest（P3） */
    public static final int PRIORITY_DEFAULT = 3;

    private final SmartMaidEntity maid;
    private final MaidTaskQueue queue = new MaidTaskQueue();
    /** 排队项 id → 运行时任务对象（挂起保留；Q8 持久化时改用 cmd+params 重建）。 */
    private final Map<String, MaidAITask> runtimeTasks = new HashMap<>();
    /** 已经 start 过的项 id（恢复时避免重复 start，直接接着 tick）。 */
    private final Set<String> startedIds = new HashSet<>();
    /** 时间窗去重：mutexKey → 最近一次入队的 tick（§8.4）。 */
    private final Map<String, Long> recentKeys = new HashMap<>();

    private int ticks;
    private int maxTicks = MAX_TASK_TICKS;
    /** 调度器自增计时（时间戳/时间窗去重用）。 */
    private long gameTick;
    /** 即时任务（setTask 路径）的 id 序号。 */
    private long soloSeq;

    /** 不可接续任务最大重跑次数（§十一）。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 长任务无进展阈值（3 分钟，仅对提供进度令牌的任务生效）。 */
    private static final int NO_PROGRESS_LIMIT = 20 * 60 * 3;
    /** 连续失败计数（每队列）：连续 2 项失败 → 放弃该队列（§十一）。 */
    private int shortFailStreak;
    private int longFailStreak;
    /** 无进展检测：上次进度令牌 + 未变化 tick 数。 */
    private String lastToken;
    private int noProgressTicks;

    public MaidTaskManager(SmartMaidEntity maid) {
        this.maid = maid;
    }

    /** 任务队列（只读访问 + 供菜单/协议读取快照）。 */
    public MaidTaskQueue queue() {
        return this.queue;
    }

    /** AI 任务是否正在接管行为决策（现有 Goal 据此让路）。队列非空也算忙，避免换项空档抖动。 */
    public boolean isAiBusy() {
        return !this.queue.isEmpty();
    }

    public MaidAITask currentTask() {
        QueuedTask cur = this.queue.current();
        return cur == null ? null : this.runtimeTasks.get(cur.id());
    }

    /**
     * 设置/抢占当前任务（<b>默认即时语义，保持不变</b>）。
     *
     * @return true 表示任务已开始；false 表示前置条件不满足
     */
    public boolean setTask(MaidAITask task) {
        // 战斗高于任务：战斗期间拒绝新任务（cancel(null) 仍放行）
        if (task != null && this.maid.isCombatActive()) {
            MaidDebug.log("任务被拒(战斗中): " + task.taskId());
            return false;
        }
        cancelCurrent();
        if (task == null) {
            return true;
        }
        if (!task.canStart(this.maid)) {
            MaidDebug.log("任务被拒: " + task.taskId());
            return false;
        }
        String id = "solo-" + (++this.soloSeq);
        QueuedTask qt = new QueuedTask(id, QueueKind.SHORT, task.taskId(), new JsonObject());
        qt.setEnqueuedAtTick(this.gameTick);
        this.queue.enqueue(qt, true);
        this.runtimeTasks.put(id, task);
        this.queue.startNext(this.gameTick);
        this.ticks = 0;
        task.start(this.maid);
        this.startedIds.add(id);
        MaidDebug.log("任务开始: " + task.taskId());
        return true;
    }

    /** 入队一个任务（不带来源信息的便捷重载）。 */
    public EnqueueResult enqueueTask(MaidAITask task, String id, String cmd, JsonObject params,
                                     QueueKind kind, boolean front, String displayName) {
        return enqueueTask(task, id, cmd, params, kind, front, displayName, null, null, PRIORITY_DEFAULT);
    }

    /**
     * 入队一个任务并做去重互斥判定（Q6；默认 {@link #setTask} 不入队）。
     *
     * @param front    true = 插到队首（P2 主人实时交互，§五.1）
     * @param groupId  一条命令展开的多项共用（§8.5；仅用于分组显示）
     * @param priority 来源优先级（{@link #PRIORITY_OWNER} / {@link #PRIORITY_DEFAULT}）
     * @return 判定结果（含拒绝原因 / 合并 / 软提示），供回执使用
     */
    public EnqueueResult enqueueTask(MaidAITask task, String id, String cmd, JsonObject params,
                                     QueueKind kind, boolean front, String displayName,
                                     String groupId, String parentLabel, int priority) {
        if (task == null) {
            return EnqueueResult.reject("invalid", null, "任务为空");
        }
        BlockPos base = this.maid != null ? this.maid.blockPosition() : null;
        String key = TaskMutex.key(cmd, params, base);

        // 1. guard 合并（唯一允许参数合并的 cmd，§8.8a）：不新增，range 取 max
        if ("guard:*".equals(key)) {
            QueuedTask existing = findByMutexKey("guard:*");
            if (existing != null) {
                int maxRange = Math.max(paramInt(existing.params(), "range", 16),
                        paramInt(params, "range", 16));
                existing.params().addProperty("range", maxRange);
                MaidAITask rt = this.runtimeTasks.get(existing.id());
                if (rt instanceof GuardTask guard) {
                    guard.setRange(maxRange);
                }
                String note = "已并入现有护卫任务，范围取最大 " + maxRange;
                MaidDebug.log("入队判定: guard:* → MERGED(" + existing.id() + ") range=" + maxRange);
                return EnqueueResult.merged(existing.id(), note);
            }
        }

        // 2. 精确同键冲突（含来源优先级，§8.6）
        boolean replaced = false;
        if (key != null) {
            QueuedTask same = findByMutexKey(key);
            if (same != null) {
                if (priority == PRIORITY_OWNER && same.priority() > PRIORITY_OWNER) {
                    // 主人显式诉求压过桌宠自动识别：移除旧的 P3，接受新项（不受时间窗限制）
                    dropExisting(same);
                    replaced = true;
                    MaidDebug.log("入队判定: " + key + " → REPLACE(P2 over P3 " + same.id() + ")");
                } else {
                    MaidDebug.log("入队判定: " + key + " → REJECT(同 " + same.id() + ")");
                    return EnqueueResult.reject("duplicate", same.id(), "已有相同任务：" + key);
                }
            }
            // 3. 时间窗去重（同键 60t 内，§8.4；P2 覆盖 P3 的情形除外）
            if (!replaced) {
                Long last = this.recentKeys.get(key);
                if (last != null && this.gameTick - last <= DEDUP_WINDOW_TICKS) {
                    MaidDebug.log("入队判定: " + key + " → REJECT(duplicate_recent)");
                    return EnqueueResult.reject("duplicate_recent", null, "刚刚已下发相同任务");
                }
            }
        }

        // 4. 泛化/精确软提示（不拒，§8.3）
        List<String> overlap = new ArrayList<>();
        if (key != null) {
            for (QueuedTask t : allQueued()) {
                if (TaskMutex.possibleOverlap(key, t.mutexKey())) {
                    overlap.add(t.id());
                }
            }
        }

        // 5. 入队
        QueuedTask qt = new QueuedTask(id, kind, cmd, params);
        qt.setDisplayName(displayName);
        qt.setMutexKey(key);
        qt.setGroupId(groupId);
        qt.setParentLabel(parentLabel);
        qt.setPriority(priority);
        qt.setEnqueuedAtTick(this.gameTick);
        if (!this.queue.enqueue(qt, front)) {
            return EnqueueResult.reject("queue_full", null, "队列已满（" + kind + "）");
        }
        this.runtimeTasks.put(id, task);
        if (key != null) {
            this.recentKeys.put(key, this.gameTick);
            pruneRecent();
        }
        String note = overlap.isEmpty() ? "已入队" : "队列已有更精确/更泛的同类任务";
        MaidDebug.log("入队判定: " + key + " → ACCEPT" + (overlap.isEmpty() ? "" : "+overlap" + overlap));
        return EnqueueResult.accepted(id, overlap, note);
    }

    /** 取消当前任务（指令 cancel / 坐下 / 主人取消）。队列其余项保留（§八）。 */
    public void cancel() {
        cancelCurrent();
        this.ticks = 0;
    }

    /** 停止指定项（长期「停止」= 永久移除，§4.3）；若该项正在执行则取消当前。 */
    public boolean stopTask(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        QueuedTask cur = this.queue.current();
        if (cur != null && cur.id().equals(id)) {
            cancelCurrent();
            return true;
        }
        boolean removed = this.queue.stop(id);
        if (removed) {
            MaidAITask rt = this.runtimeTasks.remove(id);
            this.startedIds.remove(id);
            if (rt != null) {
                rt.forceStop(this.maid);
            }
        }
        return removed;
    }

    /** 暂停当前项（USER 原因）并回队首（协议 {@code op:pauseCurrent}）。 */
    public boolean pauseCurrent() {
        if (this.queue.current() == null) {
            return false;
        }
        pauseCurrent(PauseReason.USER);
        return true;
    }

    /** 调整任务在长期/短期队列之间（协议 {@code op:setLongTerm}）；仅对排队项有效。 */
    public boolean setLongTerm(String id, boolean longTerm) {
        QueuedTask t = this.queue.findById(id);
        if (t == null) {
            return false;
        }
        QueueKind target = longTerm ? QueueKind.LONG : QueueKind.SHORT;
        if (t.queue() == target) {
            return true;
        }
        QueueKind from = t.queue();
        this.queue.remove(from, id);
        t.setQueue(target);
        if (!this.queue.enqueue(t, false)) {
            // 目标队列满 → 回退原队列
            t.setQueue(from);
            this.queue.enqueue(t, false);
            return false;
        }
        return true;
    }

    /** 整组停止（协议 {@code op:stopGroup}，§8.5）：移除该 groupId 的全部项（含正在执行的）。 */
    public int stopGroup(String groupId) {
        if (groupId == null || groupId.isEmpty()) {
            return 0;
        }
        int n = 0;
        QueuedTask cur = this.queue.current();
        if (cur != null && groupId.equals(cur.groupId())) {
            cancelCurrent();
            n++;
        }
        for (QueuedTask t : this.queue.shortItems()) {
            if (groupId.equals(t.groupId())) {
                this.runtimeTasks.remove(t.id());
                this.startedIds.remove(t.id());
                this.queue.remove(QueueKind.SHORT, t.id());
                n++;
            }
        }
        for (QueuedTask t : this.queue.longItems()) {
            if (groupId.equals(t.groupId())) {
                this.runtimeTasks.remove(t.id());
                this.startedIds.remove(t.id());
                this.queue.remove(QueueKind.LONG, t.id());
                n++;
            }
        }
        return n;
    }

    /** 记录一次失败：连续 2 项失败 → 放弃该队列（§十一）。 */
    private void noteFail(QueueKind kind) {
        if (kind == QueueKind.LONG) {
            this.longFailStreak++;
            if (this.longFailStreak >= 2) {
                MaidDebug.log("长期队列连续失败，放弃");
                this.queue.clear(QueueKind.LONG);
                this.longFailStreak = 0;
            }
        } else {
            this.shortFailStreak++;
            if (this.shortFailStreak >= 2) {
                MaidDebug.log("短期队列连续失败，放弃");
                this.queue.clear(QueueKind.SHORT);
                this.shortFailStreak = 0;
            }
        }
    }

    private void noteSuccess(QueueKind kind) {
        if (kind == QueueKind.LONG) {
            this.longFailStreak = 0;
        } else {
            this.shortFailStreak = 0;
        }
    }

    /** 只停当前项（清运行时对象 + 标记 CANCELLED），不影响排队项。 */
    private void cancelCurrent() {
        QueuedTask cur = this.queue.current();
        if (cur == null) {
            return;
        }
        MaidDebug.log("任务取消: " + cur.cmd());
        MaidAITask task = this.runtimeTasks.remove(cur.id());
        this.startedIds.remove(cur.id());
        if (task != null) {
            task.forceStop(this.maid);
        }
        this.queue.cancelCurrent();
    }

    /**
     * 暂停当前项并回队首（<b>对象保留</b>，不 forceStop、不丢引用）—— 战斗打断用（§五.2 / §六.1）。
     */
    private void pauseCurrent(PauseReason reason) {
        QueuedTask cur = this.queue.current();
        if (cur == null) {
            return;
        }
        MaidAITask task = this.runtimeTasks.get(cur.id());
        if (task != null) {
            task.onSuspend(); // 释放外部资源（如熔炉租约），恢复时重新获取
            if (task instanceof Resumable resumable) {
                cur.setProgress(resumable.saveState()); // 参数级快照（§6.2，供持久化/回溯）
            }
        }
        MaidDebug.log("任务暂停(" + reason + "): " + cur.cmd());
        this.ticks = 0;
        this.queue.pauseCurrent(reason, false);
    }

    /** 资源阻塞：暂停 + 移到本队列队尾（不占队头，让能跑的项先跑，§8.8b）。 */
    private void blockCurrent() {
        QueuedTask cur = this.queue.current();
        if (cur == null) {
            return;
        }
        QueueKind kind = cur.queue();
        pauseCurrent(PauseReason.BLOCKED);
        int last = (kind == QueueKind.LONG ? this.queue.longSize() : this.queue.shortSize()) - 1;
        if (last > 0) {
            this.queue.move(kind, cur.id(), last);
        }
        MaidDebug.log("任务阻塞让位到队尾: " + cur.cmd());
    }

    /** 每 tick 驱动（SmartMaidEntity.aiStep 服务端分支调用）。 */
    public void tick() {
        this.gameTick++;

        // 战斗高于任务：当前项暂停 + 保留对象并回队首，战斗结束自动继续（§五.2）
        if (this.maid.isCombatActive()) {
            pauseCurrent(PauseReason.COMBAT);
            return;
        }
        // 坐下 / 死亡 → 强制终止当前（不启动新任务；保持旧行为）
        if (this.maid.isOrderedToSit() || !this.maid.isAlive()) {
            cancelCurrent();
            return;
        }

        // 无当前项 → 启动队列下一项（短期优先，空则长期；已暂停项在此恢复）
        if (this.queue.current() == null) {
            startNextLoop();
        }
        QueuedTask cur = this.queue.current();
        if (cur == null) {
            return;
        }
        MaidAITask task = this.runtimeTasks.get(cur.id());
        if (task == null) {
            MaidDebug.log("运行时任务缺失，跳过: " + cur.cmd());
            this.queue.finishCurrent(State.FAILED, "运行时任务缺失");
            return;
        }

        this.ticks++;
        task.tick(this.maid);
        // 资源阻塞（如无空闲熔炉）：暂停 + 让出队头，等资源空出再恢复（§8.8b）
        if (task.isBlocked() && !task.isDone()) {
            blockCurrent();
            return;
        }
        // 长期任务无进展检测（§十一）：仅对提供进度令牌的任务生效（guard 等不提供 → 不误判）
        String token = task.progressToken();
        if (token != null) {
            if (!token.equals(this.lastToken)) {
                this.lastToken = token;
                this.noProgressTicks = 0;
            } else if (++this.noProgressTicks > NO_PROGRESS_LIMIT) {
                MaidDebug.log("长任务无进展，暂停: " + task.taskId());
                this.noProgressTicks = 0;
                pauseCurrent(PauseReason.BLOCKED);
                return;
            }
        }
        boolean timedOut = !task.isContinuous() && this.ticks > this.maxTicks;
        if (task.isDone() || timedOut) {
            String result = timedOut ? "超时" : task.result();
            State end = timedOut ? State.FAILED : State.DONE;
            MaidDebug.log("任务结束: " + task.taskId() + " result=" + result + " ticks=" + this.ticks);
            // try/finally 保证推进一定可达（§八：否则"当前项没了、队列还有、站着不动"）
            try {
                task.forceStop(this.maid);
                this.queue.finishCurrent(end, result);
                if (end == State.FAILED) {
                    noteFail(cur.queue());
                } else {
                    noteSuccess(cur.queue());
                }
            } finally {
                this.runtimeTasks.remove(cur.id());
                this.startedIds.remove(cur.id());
                this.ticks = 0;
                startNextLoop();
            }
        }
    }

    /**
     * 循环启动下一个可开始的排队项；跳过 canStart 失败的新项。
     * 已 start 过（被暂停）的项直接恢复，<b>不重复 start</b>（§六.1 零成本接续）。
     */
    private void startNextLoop() {
        while (this.queue.current() == null) {
            QueuedTask next = this.queue.peekNext();
            if (next == null) {
                return;
            }
            this.queue.startNext(this.gameTick);
            MaidAITask task = this.runtimeTasks.get(next.id());
            if (task == null) {
                MaidDebug.log("运行时任务缺失，跳过: " + next.cmd());
                this.queue.finishCurrent(State.SKIPPED, "运行时任务缺失");
                noteFail(next.queue());
                continue;
            }
            boolean resumed = this.startedIds.contains(next.id());
            if (!resumed) {
                next.setRunAttempts(next.runAttempts() + 1);
                // 不可接续任务重跑超限 → 跳过（§十一）
                if (!(task instanceof Resumable) && next.runAttempts() > MAX_ATTEMPTS) {
                    MaidDebug.log("重跑超限，跳过: " + next.cmd());
                    this.runtimeTasks.remove(next.id());
                    this.queue.finishCurrent(State.SKIPPED, "重跑超限");
                    noteFail(next.queue());
                    continue;
                }
                if (!task.canStart(this.maid)) {
                    MaidDebug.log("跳过无法开始的任务: " + next.cmd());
                    this.runtimeTasks.remove(next.id());
                    this.queue.finishCurrent(State.SKIPPED, "前置条件不满足");
                    noteFail(next.queue());
                    continue;
                }
                this.lastToken = null;
                this.noProgressTicks = 0;
                this.ticks = 0;
                task.start(this.maid);
                this.startedIds.add(next.id());
                // 跨会话回溯：有快照且环境校验通过 → 灌回中间状态（§6.2 回溯为默认、校验为兜底）
                if (next.progress() != null && task instanceof Resumable resumable
                        && resumable.validateState(this.maid, next.progress())) {
                    resumable.restoreState(next.progress());
                }
                MaidDebug.log("任务开始: " + task.taskId() + " (" + next.queue() + ")");
            } else {
                // 恢复：对象完整，直接接着 tick（挂起期间未销毁）
                task.onResume();
                this.ticks = 0;
                MaidDebug.log("任务恢复: " + task.taskId() + " (" + next.queue() + ")");
            }
            return;
        }
    }

    // ---------- 去重辅助（Q6） ----------

    /** 当前项 + 两个队列的全部项。 */
    private List<QueuedTask> allQueued() {
        List<QueuedTask> all = new ArrayList<>();
        if (this.queue.current() != null) {
            all.add(this.queue.current());
        }
        all.addAll(this.queue.shortItems());
        all.addAll(this.queue.longItems());
        return all;
    }

    private QueuedTask findByMutexKey(String key) {
        if (key == null) {
            return null;
        }
        for (QueuedTask t : allQueued()) {
            if (key.equals(t.mutexKey())) {
                return t;
            }
        }
        return null;
    }

    /** 移除一个已存在的项（可能是 current）：清运行时对象并 forceStop。 */
    private void dropExisting(QueuedTask task) {
        if (task == this.queue.current()) {
            cancelCurrent();
            return;
        }
        MaidAITask rt = this.runtimeTasks.remove(task.id());
        this.startedIds.remove(task.id());
        if (rt != null) {
            rt.forceStop(this.maid);
        }
        this.queue.remove(task.queue(), task.id());
    }

    private void pruneRecent() {
        if (this.recentKeys.size() <= 256) {
            return;
        }
        long cutoff = this.gameTick - DEDUP_WINDOW_TICKS * 4;
        this.recentKeys.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    private static int paramInt(JsonObject params, String key, int def) {
        return params.has(key) && params.get(key).isJsonPrimitive()
                && params.get(key).getAsJsonPrimitive().isNumber()
                ? params.get(key).getAsInt() : def;
    }

    // ---------- 持久化（Q8） ----------

    /** 任务快照 NBT 版本（字段增删时兼容；旧段 version 缺失视为无队列）。 */
    public static final int TASKS_VERSION = 1;

    /** 任务工厂：从指令 + 参数重建任务对象（由 bridge 层提供，避免 maidtask→bridge 依赖）。 */
    @FunctionalInterface
    public interface TaskFactory {
        MaidAITask create(SmartMaidEntity maid, String cmd, JsonObject params, StringBuilder err);
    }

    /** 把两个队列（含正在执行的项）写入 NBT {@code tasks} 段（§7.1）。 */
    public void saveToTag(CompoundTag tasks) {
        tasks.putInt("version", TASKS_VERSION);
        tasks.put("longTerm", saveList(QueueKind.LONG));
        tasks.put("shortTerm", saveList(QueueKind.SHORT));
        tasks.putLong("lastTick", this.gameTick);
    }

    private ListTag saveList(QueueKind kind) {
        ListTag list = new ListTag();
        QueuedTask cur = this.queue.current();
        if (cur != null && cur.queue() == kind) {
            list.add(taskTag(cur));
        }
        for (QueuedTask t : kind == QueueKind.LONG ? this.queue.longItems() : this.queue.shortItems()) {
            list.add(taskTag(t));
        }
        return list;
    }

    private static CompoundTag taskTag(QueuedTask t) {
        CompoundTag it = new CompoundTag();
        it.putString("id", t.id());
        it.putString("cmd", t.cmd());
        it.putString("params", t.params().toString());
        if (t.displayName() != null) {
            it.putString("name", t.displayName());
        }
        if (t.mutexKey() != null) {
            it.putString("mutexKey", t.mutexKey());
        }
        if (t.groupId() != null) {
            it.putString("group", t.groupId());
        }
        if (t.parentLabel() != null) {
            it.putString("parentLabel", t.parentLabel());
        }
        it.putInt("priority", t.priority());
        it.putInt("interrupts", t.interruptCount());
        it.putInt("attempts", t.runAttempts());
        if (t.progress() != null) {
            it.putString("progress", t.progress().toString());
        }
        return it;
    }

    /**
     * 从 NBT 恢复两个队列（§7.1）。旧存档无 {@code tasks} 段 → 视为空队列（不报错、不迁移）。
     * 恢复后调度器会在后续 tick 自动继续（短期优先），并对有快照的项做 {@code restoreState}。
     *
     * @return 恢复的任务数
     */
    public int loadFromTag(CompoundTag tasks, TaskFactory factory) {
        this.queue.clearAll();
        this.runtimeTasks.clear();
        this.startedIds.clear();
        this.recentKeys.clear();
        if (tasks == null || tasks.isEmpty() || tasks.getIntOr("version", 0) <= 0) {
            return 0;
        }
        int n = loadList(tasks.getListOrEmpty("longTerm"), QueueKind.LONG, factory);
        n += loadList(tasks.getListOrEmpty("shortTerm"), QueueKind.SHORT, factory);
        MaidDebug.log("恢复任务队列: " + n + " 项");
        return n;
    }

    private int loadList(ListTag list, QueueKind kind, TaskFactory factory) {
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            if (addLoaded(list.getCompoundOrEmpty(i), kind, factory)) {
                n++;
            }
        }
        return n;
    }

    private boolean addLoaded(CompoundTag it, QueueKind kind, TaskFactory factory) {
        String cmd = it.getStringOr("cmd", "");
        JsonObject params = parseJson(it.getStringOr("params", "{}"));
        MaidAITask task = factory.create(this.maid, cmd, params, new StringBuilder());
        if (task == null) {
            MaidDebug.log("恢复任务失败（无法重建）: " + cmd);
            return false;
        }
        String id = it.getStringOr("id", "restored-" + (++this.soloSeq));
        QueuedTask qt = new QueuedTask(id, kind, cmd, params);
        qt.setDisplayName(it.getStringOr("name", cmd));
        qt.setMutexKey(it.getStringOr("mutexKey", ""));
        qt.setGroupId(it.getStringOr("group", ""));
        qt.setPriority(it.getIntOr("priority", PRIORITY_DEFAULT));
        qt.setInterruptCount(it.getIntOr("interrupts", 0));
        qt.setRunAttempts(it.getIntOr("attempts", 0));
        String prog = it.getStringOr("progress", "");
        if (!prog.isEmpty()) {
            qt.setProgress(parseJson(prog));
        }
        if (!this.queue.enqueue(qt, false)) {
            return false;
        }
        this.runtimeTasks.put(id, task);
        return true;
    }

    private static JsonObject parseJson(String s) {
        try {
            return JsonParser.parseString(s).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    // ---------- 快照 ----------

    /** 队列快照（菜单任务页 / {@code queue op:query} 用；DESIGN_MAID_TASK_QUEUE §十）。 */
    public JsonObject snapshot() {
        JsonObject o = new JsonObject();
        QueuedTask cur = this.queue.current();
        if (cur != null) {
            o.add("current", taskJson(cur));
        } else {
            o.add("current", com.google.gson.JsonNull.INSTANCE);
        }
        o.addProperty("short_size", this.queue.shortSize());
        o.addProperty("long_size", this.queue.longSize());
        o.addProperty("combat", this.maid != null && this.maid.isCombatActive());
        JsonArray shortArr = new JsonArray();
        for (QueuedTask t : this.queue.shortItems()) {
            shortArr.add(taskJson(t));
        }
        JsonArray longArr = new JsonArray();
        for (QueuedTask t : this.queue.longItems()) {
            longArr.add(taskJson(t));
        }
        o.add("short", shortArr);
        o.add("long", longArr);
        return o;
    }

    private static JsonObject taskJson(QueuedTask t) {
        JsonObject o = new JsonObject();
        o.addProperty("id", t.id());
        o.addProperty("cmd", t.cmd());
        o.addProperty("state", t.state().name());
        o.addProperty("seq", t.seq());
        o.addProperty("queue", t.queue().name());
        if (t.mutexKey() != null) {
            o.addProperty("mutexKey", t.mutexKey());
        }
        if (t.groupId() != null) {
            o.addProperty("group", t.groupId());
        }
        if (t.parentLabel() != null) {
            o.addProperty("groupLabel", t.parentLabel());
        }
        if (t.displayName() != null) {
            o.addProperty("name", t.displayName());
        }
        if (t.pauseReason() != null) {
            o.addProperty("pauseReason", t.pauseReason().name());
        }
        o.addProperty("interrupts", t.interruptCount());
        return o;
    }

    // ---------- 入队判定结果 ----------

    /** {@link #enqueueTask} 的判定结果（供协议/菜单构造回执）。 */
    public static final class EnqueueResult {
        public final boolean ok;
        public final String reason;
        public final String existingId;
        public final boolean merged;
        public final String note;
        public final List<String> overlap;

        private EnqueueResult(boolean ok, String reason, String existingId, boolean merged,
                              String note, List<String> overlap) {
            this.ok = ok;
            this.reason = reason;
            this.existingId = existingId;
            this.merged = merged;
            this.note = note;
            this.overlap = overlap;
        }

        static EnqueueResult accepted(String id, List<String> overlap, String note) {
            return new EnqueueResult(true, null, id, false, note, overlap);
        }

        static EnqueueResult merged(String existingId, String note) {
            return new EnqueueResult(true, null, existingId, true, note, List.of());
        }

        static EnqueueResult reject(String reason, String existingId, String note) {
            return new EnqueueResult(false, reason, existingId, false, note, List.of());
        }
    }
}
