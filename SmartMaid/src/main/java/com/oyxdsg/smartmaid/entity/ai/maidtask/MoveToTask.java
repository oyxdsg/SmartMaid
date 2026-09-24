package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import net.minecraft.core.BlockPos;

/**
 * 移动到目标任务（基础指令 move_to）：持续寻路直到到达目标格（含跳跃自动生效）。
 */
public class MoveToTask extends MaidAITask {

    private final BlockPos target;
    private final double speed;
    private final double arriveRange;
    private boolean done;

    public MoveToTask(BlockPos target, double speed, double arriveRange) {
        super("move");
        this.target = target;
        this.speed = speed;
        this.arriveRange = arriveRange;
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return this.target != null;
    }

    @Override
    public void start(SmartMaidEntity maid) {
        MaidDebug.log("Move start -> " + this.target);
        this.done = MaidActions.isWithinReach(maid, this.target, this.arriveRange);
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        if (this.done) {
            return;
        }
        if (MaidActions.isWithinReach(maid, this.target, this.arriveRange)) {
            maid.getNavigation().stop();
            this.done = true;
            MaidDebug.log("Move 到达 " + this.target);
            return;
        }
        if (maid.getNavigation().isDone() || maid.tickCount % 20 == 0) {
            MaidActions.navigateTo(maid, this.target, this.speed);
        }
    }

    @Override
    public boolean isDone() {
        return this.done;
    }

    @Override
    public String result() {
        return this.done ? "已到达" : "移动中";
    }
}
