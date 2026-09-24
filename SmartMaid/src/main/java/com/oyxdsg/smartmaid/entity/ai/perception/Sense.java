package com.oyxdsg.smartmaid.entity.ai.perception;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;

/**
 * 感知通道接口：负责采集某一类感知信息，写入感知快照的对应区块。
 *
 * <p>每个通道有独立采样间隔（tick）。模块每 tick 遍历所有通道调用 {@link #collect}，
 * 未到采样间隔的通道由模块把上一份快照的同名区块复制过来（保留旧值），保证快照每 tick 完整。</p>
 *
 * <p>通道划分：self（自身）/ inventory（背包）/ owner（主人）/ nearby（周边实体）/
 * blocks（方块）/ env（环境），全部服务端执行。</p>
 */
public interface Sense {

    /**
     * 采样间隔（tick）。1 = 每 tick 采样。
     * 高频（自身/安全）用小间隔，大范围扫描（方块/实体/环境）用大间隔避免每 tick 全量扫描。
     */
    int intervalTicks();

    /** 本通道写入快照的顶层区块名（JsonObject 键，如 "self"）。 */
    String sectionName();

    /** 采样：把感知结果写入 data 的区块（覆盖式）。只在到达采样间隔时被调用。 */
    void collect(SmartMaidEntity maid, MaidPerception data);
}
