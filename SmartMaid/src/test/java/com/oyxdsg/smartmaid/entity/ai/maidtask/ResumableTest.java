package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Q7 接续快照离线验收：{@link TaskState} 坐标往返 + 任务 restoreState 灌回中间状态。 */
class ResumableTest {

    @Test
    void taskStatePosRoundTrip() {
        BlockPos p = new BlockPos(12, -34, 56);
        assertEquals(p, TaskState.readPos(TaskState.pos(p)));
        assertNull(TaskState.readPos(null));
    }

    @Test
    void taskStatePosListRoundTrip() {
        List<BlockPos> list = List.of(new BlockPos(1, 2, 3), new BlockPos(-4, 5, -6));
        List<BlockPos> back = TaskState.readPosList(TaskState.posList(list));
        assertEquals(list, back);
    }

    @Test
    void mineTaskRestoresProgress() {
        MineTask m = new MineTask(new BlockPos(0, 0, 0), 4, 9);
        JsonObject s = new JsonObject();
        s.addProperty("mined", 5);
        s.addProperty("noOre", true);
        JsonArray skipped = new JsonArray();
        skipped.add(TaskState.pos(new BlockPos(1, 1, 1)));
        s.add("skipped", skipped);

        m.restoreState(s);
        JsonObject out = m.saveState();
        assertEquals(5, out.get("mined").getAsInt());
        assertTrue(out.get("noOre").getAsBoolean());
        assertEquals(1, out.getAsJsonArray("skipped").size());
        assertTrue(m.validateState(null, s) == false, "无女仆环境校验不通过");
    }

    @Test
    void buildTaskRestoresProgress() {
        BuildTask b = new BuildTask(List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0)));
        JsonObject s = new JsonObject();
        s.addProperty("index", 2);
        s.addProperty("outOfBlock", true);
        b.restoreState(s);

        JsonObject out = b.saveState();
        assertEquals(2, out.get("index").getAsInt());
        assertTrue(out.get("outOfBlock").getAsBoolean());
        assertEquals(2, out.getAsJsonArray("positions").size());
    }

    @Test
    void farmTaskRestoresDone() {
        FarmTask f = new FarmTask(new BlockPos(0, 0, 0), 3);
        JsonObject s = new JsonObject();
        s.addProperty("done", true);
        f.restoreState(s);
        assertTrue(f.saveState().get("done").getAsBoolean());
    }
}
