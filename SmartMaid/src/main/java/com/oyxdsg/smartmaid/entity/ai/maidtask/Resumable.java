package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;

/**
 * 可接续任务契约（{@code DESIGN_MAID_TASK_QUEUE.md} §6.2）：完整中间状态快照。
 *
 * <p><b>会话内打断</b>（战斗/插队）零成本接续不依赖本接口 —— 那是"挂起对象不销毁"；
 * 本接口是给<b>跨会话</b>（退出游戏 → 重召）的<b>参数级落盘 + 状态回溯</b>准备的。</p>
 *
 * <p>策略：<b>回溯为默认、校验为兜底</b>。瞬时子状态（挖掘裂纹、寻路中间节点、挥动计时）
 * <b>不落盘</b>，恢复时该子步骤重来（§6.3）。</p>
 */
public interface Resumable {

    /** 完整中间状态（含中间变量）；不得包含 Level / 实体引用等不可序列化对象（实体存 uuid）。 */
    JsonObject saveState();

    /** 把快照灌回任务对象（在 {@code start()} 之前调用，用于跨会话回溯）。 */
    void restoreState(JsonObject state);

    /** 快照版本，字段增删时做兼容。 */
    int stateVersion();

    /** 回溯后校验环境（世界可能已变）。true = 可以按快照继续；false = 上层按"重头来/跳过"处理。 */
    boolean validateState(SmartMaidEntity maid, JsonObject state);
}
