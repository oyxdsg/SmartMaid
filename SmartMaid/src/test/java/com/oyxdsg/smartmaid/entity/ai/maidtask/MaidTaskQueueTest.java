package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.PauseReason;
import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.QueueKind;
import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.State;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务队列 Q1 离线验收：双队列调度 / 插队降位 / 重排 / 跨队列提升 / 容量。 */
class MaidTaskQueueTest {

    private static QueuedTask task(String id, QueueKind kind, String cmd) {
        return new QueuedTask(id, kind, cmd, new JsonObject());
    }

    private static List<String> ids(List<QueuedTask> items) {
        return items.stream().map(QueuedTask::id).toList();
    }

    @Test
    void enqueueAppendsToTailAndReindexes() {
        MaidTaskQueue q = new MaidTaskQueue();
        q.enqueue(task("a", QueueKind.SHORT, "mine"));
        q.enqueue(task("b", QueueKind.SHORT, "farm"));
        q.enqueue(task("c", QueueKind.SHORT, "build"));

        assertEquals(List.of("a", "b", "c"), ids(q.shortItems()));
        assertEquals(0, q.shortItems().get(0).seq());
        assertEquals(2, q.shortItems().get(2).seq());
    }

    @Test
    void enqueueFrontInsertsAtHead() {
        MaidTaskQueue q = new MaidTaskQueue();
        q.enqueue(task("a", QueueKind.SHORT, "mine"));
        q.enqueue(task("b", QueueKind.SHORT, "farm"));
        q.enqueue(task("urgent", QueueKind.SHORT, "mine"), true);

        assertEquals(List.of("urgent", "a", "b"), ids(q.shortItems()));
    }

    @Test
    void shortTermRunsFirstAndLongTermNeverStarves() {
        MaidTaskQueue q = new MaidTaskQueue();
        q.enqueue(task("L1", QueueKind.LONG, "guard"));
        q.enqueue(task("S1", QueueKind.SHORT, "mine"));

        // 短期非空：只跑短期
        assertEquals("S1", q.peekNext().id());
        q.startNext(0);
        q.finishCurrent(State.DONE, "ok");

        // 短期清空：轮到长期
        assertTrue(q.shortSize() == 0);
        assertEquals("L1", q.peekNext().id());
        q.startNext(1);
        assertSame(QueueKind.LONG, q.current().queue());
        assertEquals("L1", q.current().id());
    }

    @Test
    void startNextThenFinishAdvancesToFollowingItem() {
        MaidTaskQueue q = new MaidTaskQueue();
        q.enqueue(task("a", QueueKind.SHORT, "mine"));
        q.enqueue(task("b", QueueKind.SHORT, "farm"));

        assertEquals("a", q.startNext(0).id());
        assertEquals(State.RUNNING, q.current().state());
        assertEquals(1, q.shortSize());

        q.finishCurrent(State.DONE, "ok");
        assertNull(q.current());
        assertEquals("b", q.startNext(1).id());
        q.finishCurrent(State.DONE, "ok");
        assertNull(q.startNext(2));
    }

    @Test
    void cancelCurrentKeepsQueue() {
        MaidTaskQueue q = new MaidTaskQueue();
        q.enqueue(task("a", QueueKind.SHORT, "mine"));
        q.enqueue(task("b", QueueKind.SHORT, "farm"));
        q.startNext(0);

        assertTrue(q.cancelCurrent());
        assertNull(q.current());
        assertEquals(List.of("b"), ids(q.shortItems()));
        assertEquals("b", q.peekNext().id());
    }

    @Test
    void pauseCurrentDegradesByOneAndKeepsObject() {
        MaidTaskQueue q = new MaidTaskQueue();
        q.enqueue(task("cur", QueueKind.SHORT, "mine"));
        q.enqueue(task("keep", QueueKind.SHORT, "build"));
        q.startNext(0);

        // 插队触发：当前项暂停，降一位（在 keep 之后）
        assertTrue(q.pauseCurrent(PauseReason.PREEMPTED, true));
        q.enqueue(task("urgent", QueueKind.SHORT, "mine"), true);

        assertEquals(List.of("urgent", "keep", "cur"), ids(q.shortItems()));
        QueuedTask paused = q.findById("cur");
        assertEquals(State.PAUSED, paused.state());
        assertEquals(PauseReason.PREEMPTED, paused.pauseReason());
        assertEquals(2, paused.seq());
    }

    @Test
    void moveUpReordersAndResumeMovesToFront() {
        MaidTaskQueue q = new MaidTaskQueue();
        q.enqueue(task("a", QueueKind.SHORT, "mine"));
        q.enqueue(task("b", QueueKind.SHORT, "farm"));
        q.enqueue(task("c", QueueKind.SHORT, "build"));

        assertTrue(q.moveUp(QueueKind.SHORT, "b"));
        assertEquals(List.of("b", "a", "c"), ids(q.shortItems()));

        // 暂停当前项后恢复 → 回队首
        q.startNext(0);                              // current=b，队列 [a, c]
        q.pauseCurrent(PauseReason.PREEMPTED, true); // b 暂停降位 → [a, b, c]
        assertTrue(q.resume("b"));                   // 恢复到队首
        assertEquals("b", q.peekNext().id());
    }

    @Test
    void promoteLongTermInterruptsShortTerm() {
        MaidTaskQueue q = new MaidTaskQueue();
        q.enqueue(task("L1", QueueKind.LONG, "guard"));
        q.enqueue(task("S1", QueueKind.SHORT, "mine"));
        q.startNext(0);
        assertEquals("S1", q.current().id());

        QueuedTask promoted = q.promote("L1");
        assertEquals("L1", promoted.id());
        assertEquals("L1", q.current().id());
        // 短期项被暂停降位（保留）
        QueuedTask s1 = q.findById("S1");
        assertEquals(State.PAUSED, s1.state());
        assertEquals(PauseReason.PREEMPTED, s1.pauseReason());
    }

    @Test
    void longTermPromotedReturnsToLongHeadWhenDone() {
        MaidTaskQueue q = new MaidTaskQueue();
        q.enqueue(task("L1", QueueKind.LONG, "guard"));
        q.promote("L1");
        q.finishCurrent(State.DONE, "ok");
        assertEquals(List.of("L1"), ids(q.longItems()));
        assertEquals(State.QUEUED, q.longItems().get(0).state());
    }

    @Test
    void capacityIsEnforcedPerQueue() {
        MaidTaskQueue q = new MaidTaskQueue();
        for (int i = 0; i < MaidTaskQueue.LONG_CAPACITY; i++) {
            assertTrue(q.enqueue(task("L" + i, QueueKind.LONG, "guard")));
        }
        assertFalse(q.enqueue(task("L-over", QueueKind.LONG, "guard")));
        assertEquals(MaidTaskQueue.LONG_CAPACITY, q.longSize());
    }

    @Test
    void stopRemovesFromEitherQueuePermanently() {
        MaidTaskQueue q = new MaidTaskQueue();
        q.enqueue(task("L1", QueueKind.LONG, "guard"));
        q.enqueue(task("S1", QueueKind.SHORT, "mine"));

        assertTrue(q.stop("L1"));
        assertTrue(q.stop("S1"));
        assertFalse(q.stop("nope"));
        assertTrue(q.isEmpty());
    }
}
