package com.oyxdsg.smartmaid.entity.ai.maidtask;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Q6c 熔炉租约离线验收：占用/释放/按 owner 清理（不依赖世界）。 */
class FurnaceLeaseTest {

    @BeforeEach
    void reset() {
        FurnaceLease.clearAll();
    }

    @Test
    void acquireIsExclusivePerOwner() {
        BlockPos a = new BlockPos(1, 2, 3);
        assertTrue(FurnaceLease.acquire(a, "t1"));
        assertTrue(FurnaceLease.leasedByOther(a, "t2"), "别人应看到已占用");
        assertFalse(FurnaceLease.leasedByOther(a, "t1"), "自己不算被占");
        assertFalse(FurnaceLease.acquire(a, "t2"), "已被别人占用不能再登记");
        assertTrue(FurnaceLease.acquire(a, "t1"), "同 owner 重复登记视为成功");
    }

    @Test
    void releaseFreesForOthers() {
        BlockPos a = new BlockPos(10, 64, 10);
        FurnaceLease.acquire(a, "t1");
        FurnaceLease.release(a, "t1");
        assertFalse(FurnaceLease.leasedByOther(a, "t2"));
        assertTrue(FurnaceLease.acquire(a, "t2"));
    }

    @Test
    void releaseAllClearsOwnerLeases() {
        BlockPos a = new BlockPos(1, 2, 3);
        BlockPos b = new BlockPos(4, 5, 6);
        FurnaceLease.acquire(a, "t1");
        FurnaceLease.acquire(b, "t1");
        FurnaceLease.releaseAll("t1");
        assertFalse(FurnaceLease.leasedByOther(a, "t2"));
        assertFalse(FurnaceLease.leasedByOther(b, "t2"));
    }
}
