package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.QueueKind;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Q8 持久化离线验收：双队列 NBT 往返；旧存档无 tasks 段视为空队列。 */
class MaidTaskManagerPersistenceTest {

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

    private static MaidTaskManager.TaskFactory factory() {
        return (maid, cmd, params, err) -> new FakeTask(cmd);
    }

    @Test
    void saveLoadRoundTripKeepsBothQueues() {
        MaidTaskManager m = new MaidTaskManager(null);
        JsonObject p = new JsonObject();
        p.addProperty("range", 8);
        m.enqueueTask(new FakeTask("mine"), "a", "mine", p, QueueKind.SHORT, false, "挖矿");
        m.enqueueTask(new FakeTask("guard"), "L1", "guard", new JsonObject(),
                QueueKind.LONG, false, "护卫");
        m.enqueueTask(new FakeTask("farm"), "b", "farm", new JsonObject(),
                QueueKind.SHORT, false, "耕作");

        CompoundTag tag = new CompoundTag();
        m.saveToTag(tag);

        MaidTaskManager m2 = new MaidTaskManager(null);
        int n = m2.loadFromTag(tag, factory());
        assertEquals(3, n);
        assertEquals(2, m2.queue().shortSize());
        assertEquals(1, m2.queue().longSize());
        assertEquals("a", m2.queue().shortItems().get(0).id());
        assertEquals("L1", m2.queue().longItems().get(0).id());
    }

    @Test
    void oldSaveWithoutTasksSectionIsEmpty() {
        MaidTaskManager m = new MaidTaskManager(null);
        int n = m.loadFromTag(new CompoundTag(), factory());
        assertEquals(0, n);
        assertTrue(m.queue().isEmpty());
    }
}
