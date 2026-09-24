package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;

/**
 * AI 可执行任务抽象（M2）。
 *
 * <p>状态机约定：{@link #start} 进入（必要时换工具/锁定目标）→ 每 tick {@link #tick} 驱动 →
 * {@link #isDone} 返回 true 即结束（成功/失败由 {@link #result} 描述）。</p>
 *
 * <p>任务运行期间 {@link MaidTaskManager} 会把实体置为 aiBusy，使跟随/自动攻击/散步 Goal
 * 让路，完全接管行为决策。</p>
 */
public abstract class MaidAITask {

    private final String id;

    protected MaidAITask(String id) {
        this.id = id;
    }

    /** 任务类型 id（与指令协议 cmd 对应） */
    public final String taskId() {
        return this.id;
    }

    /** 前置条件：不满足则 setTask 拒绝（返回 false 并可回执原因） */
    public abstract boolean canStart(SmartMaidEntity maid);

    /** 进入任务：必要时换工具、锁定目标、初始化状态 */
    public abstract void start(SmartMaidEntity maid);

    /** 每 tick 驱动（由 MaidTaskManager.tick 调用） */
    public abstract void tick(SmartMaidEntity maid);

    /** 完成/失败条件（返回 true 即结束） */
    public abstract boolean isDone();

    /** 结果描述（回执用） */
    public abstract String result();

    /** 结构化结果（Atomic Command Protocol：脚本变量引用/最终回执用）。
     * 缺省返回 null，调用方退化用 {@link #result()} 字符串。 */
    public JsonObject resultJson() {
        return null;
    }

    /** 是否为持续型任务（如 guard 护卫，不因超时结束，靠 cancel/坐下终止）。默认 false。 */
    public boolean isContinuous() {
        return false;
    }

    /** 被抢占/取消/坐下时调用，清理现场（默认无操作） */
    public void forceStop(SmartMaidEntity maid) {
    }
}
