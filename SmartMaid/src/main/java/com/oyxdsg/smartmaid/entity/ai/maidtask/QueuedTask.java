package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.google.gson.JsonObject;

/**
 * 任务队列中的一项（任务队列设计 {@code DESIGN_MAID_TASK_QUEUE.md} §九）。
 *
 * <p>纯数据 + 状态机，<b>不持有运行时任务对象</b>：{@link MaidAITask} 实例由
 * {@link MaidTaskManager} 在需要挂起时另行保留（会话内打断"挂起不销毁"是零成本接续的前提）。</p>
 *
 * <p>{@code params} 与 {@code progress} 用 Gson {@link JsonObject}，与既有
 * {@code MaidAIBridge} 指令协议、NBT 持久化保持同构。</p>
 */
public final class QueuedTask {

    /** 队列类型：长期（无完成指标、常驻）/ 短期（有完成指标、做完即移除）。 */
    public enum QueueKind { LONG, SHORT }

    /** 任务项状态（§九）。 */
    public enum State { QUEUED, RUNNING, PAUSED, DONE, FAILED, SKIPPED, CANCELLED }

    /** 暂停原因（§九 + §8.8b）。 */
    public enum PauseReason { COMBAT, PREEMPTED, LONGTERM_SUSPEND, USER, BLOCKED }

    private final String id;
    private QueueKind queue;
    private final String cmd;
    private final JsonObject params;

    private String mutexKey;
    private String groupId;
    private String parentLabel;
    private String displayName;
    /** 来源优先级：2 = 主人显式（P2，压过桌宠 P3）；3 = 桌宠事件线/脚本（P3）。 */
    private int priority = 3;

    private State state = State.QUEUED;
    private PauseReason pauseReason;
    private int seq;
    /** 被打断次数（打断 = 降一位，绝不取消；§五.3）。 */
    private int interruptCount;
    /** 重跑次数（仅"不可接续"任务计数；§十一）。 */
    private int runAttempts;
    /** Resumable.saveState() 的中间变量（§六.4）。 */
    private JsonObject progress;

    private long enqueuedAtTick;
    private long startedAtTick;
    private long lastActiveTick;
    private String result;

    public QueuedTask(String id, QueueKind queue, String cmd, JsonObject params) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("QueuedTask id 不能为空");
        }
        this.id = id;
        this.queue = queue;
        this.cmd = cmd;
        this.params = params != null ? params : new JsonObject();
    }

    public String id() {
        return this.id;
    }

    public QueueKind queue() {
        return this.queue;
    }

    public void setQueue(QueueKind queue) {
        this.queue = queue;
    }

    public String cmd() {
        return this.cmd;
    }

    public JsonObject params() {
        return this.params;
    }

    public String mutexKey() {
        return this.mutexKey;
    }

    public void setMutexKey(String mutexKey) {
        this.mutexKey = mutexKey;
    }

    public String groupId() {
        return this.groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String parentLabel() {
        return this.parentLabel;
    }

    public void setParentLabel(String parentLabel) {
        this.parentLabel = parentLabel;
    }

    public String displayName() {
        return this.displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public int priority() {
        return this.priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public State state() {
        return this.state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public PauseReason pauseReason() {
        return this.pauseReason;
    }

    public void setPauseReason(PauseReason pauseReason) {
        this.pauseReason = pauseReason;
    }

    public int seq() {
        return this.seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public int interruptCount() {
        return this.interruptCount;
    }

    public void setInterruptCount(int interruptCount) {
        this.interruptCount = interruptCount;
    }

    public int runAttempts() {
        return this.runAttempts;
    }

    public void setRunAttempts(int runAttempts) {
        this.runAttempts = runAttempts;
    }

    public JsonObject progress() {
        return this.progress;
    }

    public void setProgress(JsonObject progress) {
        this.progress = progress;
    }

    public long enqueuedAtTick() {
        return this.enqueuedAtTick;
    }

    public void setEnqueuedAtTick(long enqueuedAtTick) {
        this.enqueuedAtTick = enqueuedAtTick;
    }

    public long startedAtTick() {
        return this.startedAtTick;
    }

    public void setStartedAtTick(long startedAtTick) {
        this.startedAtTick = startedAtTick;
    }

    public long lastActiveTick() {
        return this.lastActiveTick;
    }

    public void setLastActiveTick(long lastActiveTick) {
        this.lastActiveTick = lastActiveTick;
    }

    public String result() {
        return this.result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    @Override
    public String toString() {
        return "QueuedTask{" + this.id + " " + this.queue + " " + this.cmd
                + " " + this.state + (this.pauseReason != null ? "/" + this.pauseReason : "") + "}";
    }
}
