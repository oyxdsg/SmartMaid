package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;

/**
 * 一次性动作任务（基础指令）：start 时立即执行一个原子动作然后完成。
 *
 * <p>用于 equip / store / drop / break / place / use / look / sit / stop 等
 * 不需要持续 tick 驱动的基础指令。</p>
 */
public class OneShotTask extends MaidAITask {

    private final String desc;
    private final Runnable action;
    private boolean done;

    public OneShotTask(String id, String desc, Runnable action) {
        super(id);
        this.desc = desc;
        this.action = action;
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return true;
    }

    @Override
    public void start(SmartMaidEntity maid) {
        try {
            this.action.run();
        } catch (Exception e) {
            SmartMaid.LOGGER.error("OneShot 任务执行异常: " + taskId(), e);
        }
        this.done = true;
    }

    @Override
    public void tick(SmartMaidEntity maid) {
    }

    @Override
    public boolean isDone() {
        return this.done;
    }

    @Override
    public String result() {
        return this.desc;
    }
}
