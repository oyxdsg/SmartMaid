package com.oyxdsg.smartmaid.entity.ai.maidtask;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/**
 * 熔炉租约（{@code DESIGN_MAID_TASK_QUEUE.md} §8.8b）：<b>一个熔炉同一时间只能被一个
 * {@code smelt} 任务占用</b>（不允许混烧，进度不可控）。
 *
 * <p>服务端内存表 {@code furnacePos -> ownerTaskId}，<b>不持久化</b>（跨存档无意义，
 * 重召后重新扫描环境自然重建）。"空闲"= ① 未被其他任务登记；② 熔炉输入槽为空。</p>
 */
public final class FurnaceLease {

    private static final Map<BlockPos, String> LEASES = new HashMap<>();

    private FurnaceLease() {
    }

    /** 该熔炉是否被"其他任务"登记。 */
    public static synchronized boolean leasedByOther(BlockPos pos, String ownerId) {
        String owner = LEASES.get(pos);
        return owner != null && !owner.equals(ownerId);
    }

    /** 登记熔炉给 ownerId；已被别人占用返回 false（同一 owner 重复登记视为成功）。 */
    public static synchronized boolean acquire(BlockPos pos, String ownerId) {
        if (pos == null) {
            return false;
        }
        String owner = LEASES.get(pos);
        if (owner != null && !owner.equals(ownerId)) {
            return false;
        }
        LEASES.put(pos.immutable(), ownerId);
        return true;
    }

    /** 释放（仅当持有者是 ownerId）。 */
    public static synchronized void release(BlockPos pos, String ownerId) {
        if (pos == null) {
            return;
        }
        if (ownerId.equals(LEASES.get(pos))) {
            LEASES.remove(pos);
        }
    }

    /** 释放该 owner 持有的全部熔炉（forceStop / 异常兜底；防租约泄漏，§十一）。 */
    public static synchronized void releaseAll(String ownerId) {
        LEASES.entrySet().removeIf(e -> e.getValue().equals(ownerId));
    }

    /** 熔炉"空闲"= 输入槽(0) 为空（正在烧的不算空闲）。 */
    public static boolean isIdle(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace
                && furnace.getItem(0).isEmpty();
    }

    /** 找最近的空闲熔炉（输入槽空 + 未被其他任务登记）；没有返回 null。 */
    public static BlockPos findFree(Level level, BlockPos center, int range, String ownerId) {
        return BlockPos.betweenClosedStream(
                        center.offset(-range, -range, -range), center.offset(range, range, range))
                .filter(pos -> level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity)
                .map(BlockPos::immutable)
                .filter(pos -> !leasedByOther(pos, ownerId))
                .filter(pos -> isIdle(level, pos))
                .min(Comparator.comparingDouble(pos -> pos.distSqr(center)))
                .orElse(null);
    }

    /** 范围内是否存在任何熔炉（区分"无熔炉"与"熔炉全忙"）。 */
    public static boolean anyFurnace(Level level, BlockPos center, int range) {
        return BlockPos.betweenClosedStream(
                        center.offset(-range, -range, -range), center.offset(range, range, range))
                .anyMatch(pos -> level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity);
    }

    /** 测试用：清空租约表。 */
    static synchronized void clearAll() {
        LEASES.clear();
    }
}
