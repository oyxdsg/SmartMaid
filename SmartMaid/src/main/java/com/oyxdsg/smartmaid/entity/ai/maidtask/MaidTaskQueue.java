package com.oyxdsg.smartmaid.entity.ai.maidtask;

import java.util.ArrayList;
import java.util.List;

import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.PauseReason;
import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.QueueKind;
import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.State;

/**
 * 女仆任务队列（任务队列设计 §四 / §五 / §九）。
 *
 * <p><b>双队列模型</b>：长期队列（无完成指标、常驻）+ 短期队列（有完成指标、做完即移除）。
 * 调度规则：<b>短期非空 → 只跑短期队首；短期清空 → 跑长期队首</b>（保证长期不被短期饿死）。</p>
 *
 * <p>本类是<b>纯服务端内存数据结构</b>：不触碰世界、不构造 {@link MaidAITask}、不做持久化。
 * 任务对象由 {@link MaidTaskManager} 持有；队列只记 {@link QueuedTask} 数据。
 * 会话内打断采用"<b>暂停 + 降一位 + 对象保留</b>"（§五.3），绝不丢弃。</p>
 */
public final class MaidTaskQueue {

    /** 短期队列上限（§4.4）。 */
    public static final int SHORT_CAPACITY = 16;
    /** 长期队列上限（§4.4）。 */
    public static final int LONG_CAPACITY = 4;

    private final List<QueuedTask> shortQueue = new ArrayList<>();
    private final List<QueuedTask> longQueue = new ArrayList<>();
    /** 正在执行的那一项（从队列取出的单独持有；null 表示空闲）。 */
    private QueuedTask current;

    // ---------- 查询 ----------

    public QueuedTask current() {
        return this.current;
    }

    public boolean hasCurrent() {
        return this.current != null;
    }

    public int shortSize() {
        return this.shortQueue.size();
    }

    public int longSize() {
        return this.longQueue.size();
    }

    public boolean isEmpty() {
        return this.current == null && this.shortQueue.isEmpty() && this.longQueue.isEmpty();
    }

    public List<QueuedTask> shortItems() {
        return List.copyOf(this.shortQueue);
    }

    public List<QueuedTask> longItems() {
        return List.copyOf(this.longQueue);
    }

    /** 按 id 在两个队列（不含 current）中查找。 */
    public QueuedTask findById(String id) {
        QueuedTask t = findIn(this.shortQueue, id);
        return t != null ? t : findIn(this.longQueue, id);
    }

    // ---------- 入队 ----------

    /** 追加到对应队列（默认队尾）。容量满返回 false。 */
    public boolean enqueue(QueuedTask task) {
        return enqueue(task, false);
    }

    /**
     * 入队。
     *
     * @param front true = 插到队首（P2 主人实时交互插队，§五.1）
     * @return false = 队列已满（调用方应回执 {@code queue_full}）
     */
    public boolean enqueue(QueuedTask task, boolean front) {
        List<QueuedTask> q = list(task.queue());
        if (q.size() >= capacity(task.queue())) {
            return false;
        }
        task.setState(State.QUEUED);
        task.setPauseReason(null);
        if (front) {
            q.add(0, task);
        } else {
            q.add(task);
        }
        reindex(task.queue());
        return true;
    }

    // ---------- 调度 ----------

    /** 下一个该执行的任务：短期队首优先，短期空则长期队首（§四.1）。 */
    public QueuedTask peekNext() {
        if (!this.shortQueue.isEmpty()) {
            return this.shortQueue.get(0);
        }
        if (!this.longQueue.isEmpty()) {
            return this.longQueue.get(0);
        }
        return null;
    }

    /**
     * 启动下一项：取出并置 {@link State#RUNNING}，作为 {@link #current()}。
     *
     * @return 启动的项；null = 两个队列都空
     */
    public QueuedTask startNext(long tick) {
        QueuedTask next = peekNext();
        if (next == null) {
            return null;
        }
        list(next.queue()).remove(next);
        reindex(next.queue());
        next.setState(State.RUNNING);
        next.setPauseReason(null);
        next.setStartedAtTick(tick);
        next.setLastActiveTick(tick);
        this.current = next;
        return next;
    }

    /** 标记当前项结束并清空 current（§四.3：长期项跑完回到长期队首）。 */
    public void finishCurrent(State endState, String result) {
        if (this.current == null) {
            return;
        }
        this.current.setState(endState);
        this.current.setResult(result);
        QueuedTask done = this.current;
        this.current = null;
        // 长期项"设为当前"后跑完 → 回到长期队列队首（§4.3）
        if (done.queue() == QueueKind.LONG && endState == State.DONE) {
            done.setState(State.QUEUED);
            this.longQueue.add(0, done);
            reindex(QueueKind.LONG);
        }
    }

    /** 更新当前项的活跃时间（供无进展检测）。 */
    public void touch(long tick) {
        if (this.current != null) {
            this.current.setLastActiveTick(tick);
        }
    }

    // ---------- 取消 / 暂停 ----------

    /** 停止当前项，队列其余项保留（"停止这一项"，§八）。 */
    public boolean cancelCurrent() {
        if (this.current == null) {
            return false;
        }
        this.current.setState(State.CANCELLED);
        this.current = null;
        return true;
    }

    /**
     * 暂停当前项并放回队列（<b>对象保留</b>）。
     *
     * @param degrade true = 降一位（插到队首之后，§五.3）；false = 回到队首
     * @return false = 当前无任务
     */
    public boolean pauseCurrent(PauseReason reason, boolean degrade) {
        if (this.current == null) {
            return false;
        }
        QueuedTask t = this.current;
        this.current = null;
        t.setState(State.PAUSED);
        t.setPauseReason(reason);
        t.setInterruptCount(t.interruptCount() + 1);
        List<QueuedTask> q = list(t.queue());
        int idx = degrade ? Math.min(1, q.size()) : 0;
        q.add(idx, t);
        reindex(t.queue());
        return true;
    }

    /** 恢复某暂停项：回到该队列队首并置 {@link State#QUEUED}。 */
    public boolean resume(String id) {
        for (QueueKind kind : QueueKind.values()) {
            List<QueuedTask> q = list(kind);
            for (int i = 0; i < q.size(); i++) {
                QueuedTask t = q.get(i);
                if (t.id().equals(id) && t.state() == State.PAUSED) {
                    q.remove(i);
                    t.setState(State.QUEUED);
                    t.setPauseReason(null);
                    q.add(0, t);
                    reindex(kind);
                    return true;
                }
            }
        }
        return false;
    }

    // ---------- 移除 / 重排 ----------

    /** 从指定队列移出某项。 */
    public boolean remove(QueueKind kind, String id) {
        List<QueuedTask> q = list(kind);
        for (int i = 0; i < q.size(); i++) {
            if (q.get(i).id().equals(id)) {
                q.remove(i);
                reindex(kind);
                return true;
            }
        }
        return false;
    }

    /** 永久移除（长期队列的"停止"= 主人喊停，§4.3），从两个队列移除。 */
    public boolean stop(String id) {
        boolean removed = remove(QueueKind.SHORT, id);
        removed |= remove(QueueKind.LONG, id);
        return removed;
    }

    /** 队列内上移一位（调顺序）。 */
    public boolean moveUp(QueueKind kind, String id) {
        List<QueuedTask> q = list(kind);
        for (int i = 0; i < q.size(); i++) {
            if (q.get(i).id().equals(id)) {
                if (i == 0) {
                    return false;
                }
                QueuedTask t = q.remove(i);
                q.add(i - 1, t);
                reindex(kind);
                return true;
            }
        }
        return false;
    }

    /** 队列内移动到指定索引（跨位置调顺序）。 */
    public boolean move(QueueKind kind, String id, int toIndex) {
        List<QueuedTask> q = list(kind);
        if (toIndex < 0 || toIndex >= q.size()) {
            return false;
        }
        for (int i = 0; i < q.size(); i++) {
            if (q.get(i).id().equals(id)) {
                QueuedTask t = q.remove(i);
                q.add(toIndex, t);
                reindex(kind);
                return true;
            }
        }
        return false;
    }

    /**
     * 长期项「设为当前」：立刻取得执行权，<b>可打断短期任务</b>（§4.3）。
     *
     * <p>被占用的当前项 {@link State#PAUSED}+降一位并保留。</p>
     *
     * @return 被提升的长期项；null = 未找到该长期项
     */
    public QueuedTask promote(String id) {
        QueuedTask target = findIn(this.longQueue, id);
        if (target == null) {
            return null;
        }
        this.longQueue.remove(target);
        reindex(QueueKind.LONG);
        if (this.current != null) {
            pauseCurrent(PauseReason.PREEMPTED, true);
        }
        target.setState(State.RUNNING);
        target.setPauseReason(null);
        this.current = target;
        return target;
    }

    // ---------- 清空 ----------

    /** 清空某队列（不影响 current）。 */
    public void clear(QueueKind kind) {
        list(kind).clear();
    }

    /** 清空全部（含 current）。 */
    public void clearAll() {
        this.shortQueue.clear();
        this.longQueue.clear();
        this.current = null;
    }

    // ---------- 内部 ----------

    private List<QueuedTask> list(QueueKind kind) {
        return kind == QueueKind.LONG ? this.longQueue : this.shortQueue;
    }

    private int capacity(QueueKind kind) {
        return kind == QueueKind.LONG ? LONG_CAPACITY : SHORT_CAPACITY;
    }

    private static QueuedTask findIn(List<QueuedTask> q, String id) {
        for (QueuedTask t : q) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return null;
    }

    private void reindex(QueueKind kind) {
        List<QueuedTask> q = list(kind);
        for (int i = 0; i < q.size(); i++) {
            q.get(i).setSeq(i);
        }
    }
}
