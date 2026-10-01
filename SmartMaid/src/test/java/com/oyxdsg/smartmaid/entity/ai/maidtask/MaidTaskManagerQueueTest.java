package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.QueueKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q2/Q6 入队层离线验收：{@link MaidTaskManager#enqueueTask} 的判重、来源优先级、guard 合并。
 * 用不触碰世界的假任务；{@code maid} 传 null（入队路径不访问实体）。
 */
class MaidTaskManagerQueueTest {

    private static final class FakeTask extends MaidAITask {
        FakeTask(String id) {
            super(id);
        }

        @Override
        public boolean canStart(SmartMaidEntity maid) {
            return true;
        }

        @Override
        public void start(SmartMaidEntity maid) {
        }

        @Override
        public void tick(SmartMaidEntity maid) {
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public String result() {
            return "fake";
        }
    }

    private static JsonObject item(String id) {
        JsonObject p = new JsonObject();
        p.addProperty("item", id);
        return p;
    }

    private static JsonObject pos(int x, int y, int z) {
        JsonObject p = new JsonObject();
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        p.add("pos", a);
        return p;
    }

    @Test
    void isAiBusyIsTrueWhenQueueNonEmpty() {
        MaidTaskManager m = new MaidTaskManager(null);
        assertFalse(m.isAiBusy());
        assertTrue(m.enqueueTask(new FakeTask("mine"), "q1", "mine", new JsonObject(),
                QueueKind.SHORT, false, "挖矿").ok);
        assertTrue(m.isAiBusy(), "有排队项时应忙（current 为空也算）");
        assertEquals(1, m.queue().shortSize());
    }

    @Test
    void enqueueRespectsCapacity() {
        MaidTaskManager m = new MaidTaskManager(null);
        // 用子目不同的 smelt（不同 mutexKey）填满长期队列；guard 会触发合并语义，不能用于此测试
        String[] items = {"minecraft:iron_ingot", "minecraft:gold_ingot",
                "minecraft:copper_ingot", "minecraft:diamond"};
        assertEquals(MaidTaskQueue.LONG_CAPACITY, items.length);
        for (int i = 0; i < items.length; i++) {
            assertTrue(m.enqueueTask(new FakeTask("smelt"), "L" + i, "smelt", item(items[i]),
                    QueueKind.LONG, false, "烧炼").ok);
        }
        MaidTaskManager.EnqueueResult over = m.enqueueTask(new FakeTask("smelt"), "L-over", "smelt",
                item("minecraft:netherite_ingot"), QueueKind.LONG, false, "烧炼");
        assertFalse(over.ok);
        assertEquals("queue_full", over.reason);
    }

    @Test
    void snapshotReflectsBothQueues() {
        MaidTaskManager m = new MaidTaskManager(null);
        m.enqueueTask(new FakeTask("mine"), "s1", "mine", new JsonObject(), QueueKind.SHORT, false, "挖矿");
        m.enqueueTask(new FakeTask("guard"), "L1", "guard", new JsonObject(), QueueKind.LONG, false, "护卫");

        JsonObject snap = m.snapshot();
        assertEquals(1, snap.get("short_size").getAsInt());
        assertEquals(1, snap.get("long_size").getAsInt());
        assertTrue(snap.get("current").isJsonNull());
        assertEquals("s1", snap.getAsJsonArray("short").get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    void duplicateSameSubTargetRejected() {
        MaidTaskManager m = new MaidTaskManager(null);
        assertTrue(m.enqueueTask(new FakeTask("smelt"), "a", "smelt", item("minecraft:iron_ingot"),
                QueueKind.SHORT, false, "烧铁锭").ok);
        MaidTaskManager.EnqueueResult dup = m.enqueueTask(new FakeTask("smelt"), "b", "smelt",
                item("minecraft:iron_ingot"), QueueKind.SHORT, false, "烧铁锭");
        assertFalse(dup.ok);
        assertEquals("duplicate", dup.reason);
        assertEquals("a", dup.existingId);
        assertEquals(1, m.queue().shortSize());
    }

    @Test
    void differentSubTargetsCoexist() {
        MaidTaskManager m = new MaidTaskManager(null);
        assertTrue(m.enqueueTask(new FakeTask("smelt"), "a", "smelt", item("minecraft:iron_ingot"),
                QueueKind.SHORT, false, "烧铁锭").ok);
        assertTrue(m.enqueueTask(new FakeTask("smelt"), "b", "smelt", item("minecraft:gold_ingot"),
                QueueKind.SHORT, false, "烧金锭").ok, "子目不同应可共存（烧所有矿物）");
        assertEquals(2, m.queue().shortSize());
    }

    @Test
    void generalVsExactGivesSoftOverlap() {
        MaidTaskManager m = new MaidTaskManager(null);
        m.enqueueTask(new FakeTask("mine"), "a", "mine", pos(10, 64, 10), QueueKind.SHORT, false, "挖矿");
        MaidTaskManager.EnqueueResult r = m.enqueueTask(new FakeTask("mine"), "b", "mine",
                new JsonObject(), QueueKind.SHORT, false, "挖矿");
        assertTrue(r.ok, "泛化请求不该被精确项挡住");
        assertTrue(r.overlap.contains("a"), "应带 possible_overlap 软提示");
    }

    @Test
    void timeWindowRejectsRecentRepeat() {
        MaidTaskManager m = new MaidTaskManager(null);
        m.enqueueTask(new FakeTask("smelt"), "a", "smelt", item("minecraft:iron_ingot"),
                QueueKind.SHORT, false, "烧铁锭");
        // 移除后同键再发（60t 窗口内）→ duplicate_recent
        m.queue().remove(QueueKind.SHORT, "a");
        MaidTaskManager.EnqueueResult r = m.enqueueTask(new FakeTask("smelt"), "b", "smelt",
                item("minecraft:iron_ingot"), QueueKind.SHORT, false, "烧铁锭");
        assertFalse(r.ok);
        assertEquals("duplicate_recent", r.reason);
    }

    @Test
    void ownerPriorityReplacesDefault() {
        MaidTaskManager m = new MaidTaskManager(null);
        m.enqueueTask(new FakeTask("smelt"), "a", "smelt", item("minecraft:iron_ingot"),
                QueueKind.SHORT, false, "烧铁锭", null, null, MaidTaskManager.PRIORITY_DEFAULT);
        MaidTaskManager.EnqueueResult r = m.enqueueTask(new FakeTask("smelt"), "b", "smelt",
                item("minecraft:iron_ingot"), QueueKind.SHORT, false, "烧铁锭",
                null, null, MaidTaskManager.PRIORITY_OWNER);
        assertTrue(r.ok, "主人 P2 应压过桌宠 P3");
        assertEquals(1, m.queue().shortSize());
        assertEquals(MaidTaskManager.PRIORITY_OWNER, m.queue().shortItems().get(0).priority());
    }

    @Test
    void guardMergesTakingMaxRange() {
        MaidTaskManager m = new MaidTaskManager(null);
        JsonObject g10 = new JsonObject();
        g10.addProperty("range", 10);
        m.enqueueTask(new FakeTask("guard"), "g1", "guard", g10, QueueKind.LONG, false, "护卫");
        JsonObject g12 = new JsonObject();
        g12.addProperty("range", 12);
        MaidTaskManager.EnqueueResult r = m.enqueueTask(new FakeTask("guard"), "g2", "guard", g12,
                QueueKind.LONG, false, "护卫");
        assertTrue(r.ok && r.merged, "已有 guard 时应合并");
        assertEquals(1, m.queue().longSize(), "不新增任务");
        assertEquals(12, m.queue().longItems().get(0).params().get("range").getAsInt(), "range 取最大");
    }

    @Test
    void setLongTermMovesBetweenQueues() {
        MaidTaskManager m = new MaidTaskManager(null);
        m.enqueueTask(new FakeTask("mine"), "a", "mine", new JsonObject(),
                QueueKind.SHORT, false, "挖矿");
        assertTrue(m.setLongTerm("a", true));
        assertEquals(0, m.queue().shortSize());
        assertEquals(1, m.queue().longSize());
        assertTrue(m.setLongTerm("a", false));
        assertEquals(1, m.queue().shortSize());
    }

    @Test
    void stopGroupRemovesWholeGroup() {
        MaidTaskManager m = new MaidTaskManager(null);
        m.enqueueTask(new FakeTask("smelt"), "a", "smelt", item("minecraft:iron_ingot"),
                QueueKind.SHORT, false, "烧铁", "g1", "烧所有矿物", 3);
        m.enqueueTask(new FakeTask("smelt"), "b", "smelt", item("minecraft:gold_ingot"),
                QueueKind.SHORT, false, "烧金", "g1", "烧所有矿物", 3);
        assertEquals(2, m.stopGroup("g1"));
        assertEquals(0, m.queue().shortSize());
    }
}
