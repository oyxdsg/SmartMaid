package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import net.minecraft.world.entity.LivingEntity;

/**
 * 护卫任务（M3，持续型）：主动索敌并攻击周围敌人，无目标时原地待命。
 * 不因超时结束，靠 cancel / 坐下终止。
 */
public class GuardTask extends MaidAITask {

    private final int range;
    private final boolean defensive;
    private LivingEntity target;
    private int cooldown;
    private static final int ATTACK_COOLDOWN = 20;

    public GuardTask(int range, boolean defensive) {
        super("guard");
        this.range = range;
        this.defensive = defensive;
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return true;
    }

    @Override
    public boolean isContinuous() {
        return true;
    }

    @Override
    public void start(SmartMaidEntity maid) {
        this.target = null;
        this.cooldown = 0;
        MaidDebug.log("Guard start range=" + range + " defensive=" + defensive);
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        if (this.target == null || !this.target.isAlive()) {
            // 找下一个目标（defensive 只反击打主人的敌人）
            this.target = findTarget(maid);
            return;
        }
        maid.getLookControl().setLookAt(this.target, 10.0F, (float) maid.getMaxHeadXRot());
        double distSqr = maid.distanceToSqr(this.target);
        if (distSqr > 2.5D * 2.5D) {
            if (maid.getNavigation().isDone() || maid.tickCount % 20 == 0) {
                maid.getNavigation().moveTo(this.target, 1.0D);
            }
        } else {
            maid.getNavigation().stop();
            if (--this.cooldown <= 0) {
                MaidActions.attack(maid, this.target);
                this.cooldown = ATTACK_COOLDOWN;
            }
        }
    }

    @Override
    public boolean isDone() {
        return false; // 持续型，靠 cancel/坐下终止
    }

    @Override
    public String result() {
        return "护卫中";
    }

    private LivingEntity findTarget(SmartMaidEntity maid) {
        if (this.defensive && maid.getLastHurtByMob() != null && maid.getLastHurtByMob().isAlive()) {
            return maid.getLastHurtByMob();
        }
        return AttackTask.findNearestHostile(maid, this.range);
    }
}
