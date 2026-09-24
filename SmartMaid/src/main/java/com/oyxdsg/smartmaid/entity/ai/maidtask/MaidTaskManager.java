package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;

/**
 * AI 任务调度器（M2）：每 tick 驱动当前任务，管理抢占/取消/超时/aiBusy 标志。
 *
 * <p>与现有 Goal 协作：任务激活时 {@link #isAiBusy()} 返回 true，使
 * {@code MaidFollowGoal} / {@code MaidCombatGoal} / 散步 Goal 让路（其 canUse 检查该标志）；
 * L0 安全层与跳跃执行器不受影响。</p>
 *
 * <p>战斗优先级高于任务：{@link SmartMaidEntity#isCombatActive()} 为真时本调度器拒绝新任务
 * 并取消当前任务（战斗接管行为决策）。</p>
 */
public class MaidTaskManager {

    /** 任务最长运行 tick（60 秒兜底，防任务卡死） */
    private static final int MAX_TASK_TICKS = 20 * 60;

    private final SmartMaidEntity maid;
    private MaidAITask current;
    private int ticks;
    private int maxTicks = MAX_TASK_TICKS;

    public MaidTaskManager(SmartMaidEntity maid) {
        this.maid = maid;
    }

    /** AI 任务是否正在接管行为决策（现有 Goal 据此让路） */
    public boolean isAiBusy() {
        return this.current != null;
    }

    public MaidAITask currentTask() {
        return this.current;
    }

    /**
     * 设置/抢占当前任务。
     *
     * @return true 表示任务已开始；false 表示前置条件不满足（可查 {@link #lastRejectReason()}）
     */
    public boolean setTask(MaidAITask task) {
        // 战斗高于任务：战斗期间拒绝新任务（cancel(null) 仍放行）
        if (task != null && this.maid.isCombatActive()) {
            MaidDebug.log("任务被拒(战斗中): " + task.taskId());
            return false;
        }
        if (this.current != null) {
            this.current.forceStop(this.maid);
        }
        if (task == null) {
            this.current = null;
            return true;
        }
        if (!task.canStart(this.maid)) {
            MaidDebug.log("任务被拒: " + task.taskId());
            return false;
        }
        this.current = task;
        this.ticks = 0;
        task.start(this.maid);
        MaidDebug.log("任务开始: " + task.taskId());
        return true;
    }

    /** 取消当前任务（指令 cancel / 坐下 / 主人取消） */
    public void cancel() {
        if (this.current != null) {
            MaidDebug.log("任务取消: " + this.current.taskId());
            this.current.forceStop(this.maid);
            this.current = null;
        }
        this.ticks = 0;
    }

    /** 每 tick 驱动（SmartMaidEntity.aiStep 服务端分支调用） */
    public void tick() {
        if (this.current == null) {
            return;
        }
        // 战斗高于任务：进入战斗即取消当前任务
        if (this.maid.isCombatActive()) {
            MaidDebug.log("战斗中，取消任务: " + this.current.taskId());
            this.cancel();
            return;
        }
        // 坐下 / 死亡 → 强制终止
        if (this.maid.isOrderedToSit() || !this.maid.isAlive()) {
            this.cancel();
            return;
        }
        this.ticks++;
        this.current.tick(this.maid);
        boolean timedOut = !this.current.isContinuous() && this.ticks > this.maxTicks;
        if (this.current != null && (this.current.isDone() || timedOut)) {
            MaidDebug.log("任务结束: " + this.current.taskId()
                    + " result=" + this.current.result()
                    + " ticks=" + this.ticks);
            this.cancel();
        }
    }
}
