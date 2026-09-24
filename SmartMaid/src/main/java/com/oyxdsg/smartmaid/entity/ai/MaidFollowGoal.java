package com.oyxdsg.smartmaid.entity.ai;

import com.oyxdsg.smartmaid.data.MaidSettings;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * L1 规则 — 跟随主人（P3 跟随规则）。
 *
 * <p>规则条件与动作（确定性）：</p>
 * <ul>
 *     <li>与主人距离 &gt; {@code startDistance} → 启动跟随</li>
 *     <li>与主人距离 &gt; {@code stopDistance} → 持续向主人移动</li>
 *     <li>坐下时 / 无主人 / 设置关闭跟随 → 不启动</li>
 * </ul>
 *
 * <p>跟随距离与开关来自玩家绑定的 {@link MaidSettings}，实时读取（菜单改完立即生效）。</p>
 */
public class MaidFollowGoal extends Goal {
    private final SmartMaidEntity maid;
    private LivingEntity owner;
    private final double speedModifier;
    private int ticksToRecomputePath;

    public MaidFollowGoal(SmartMaidEntity maid, double speedModifier) {
        this.maid = maid;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private float startDistance() {
        return MaidSettings.FOLLOW_START_STEPS[this.maid.getSettings().getFollowStartIndex()];
    }

    private float stopDistance() {
        return MaidSettings.FOLLOW_STOP_STEPS[this.maid.getSettings().getFollowStopIndex()];
    }

    @Override
    public boolean canUse() {
        // AI 任务运行时跟随让路（任务接管行为决策）；设置关闭跟随时不启动
        if (this.maid.isAiBusy() || !this.maid.getSettings().isFollowEnabled()) {
            return false;
        }
        LivingEntity owner = this.maid.getOwner();
        if (owner == null || owner.isSpectator() || this.maid.isOrderedToSit()) {
            return false;
        }
        float start = this.startDistance();
        if (this.maid.distanceToSqr(owner) < (double) (start * start)) {
            return false;
        }
        this.owner = owner;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.owner == null || this.maid.isOrderedToSit() || !this.maid.getSettings().isFollowEnabled()) {
            return false;
        }
        float stop = this.stopDistance();
        return this.maid.distanceToSqr(this.owner) > (double) (stop * stop);
    }

    @Override
    public void start() {
        this.ticksToRecomputePath = 0;
    }

    @Override
    public void stop() {
        this.owner = null;
        this.maid.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.owner == null || this.maid.isOrderedToSit()) {
            return;
        }
        this.maid.getLookControl().setLookAt(this.owner, 10.0F, (float) this.maid.getMaxHeadXRot());
        if (--this.ticksToRecomputePath <= 0) {
            this.ticksToRecomputePath = 10;
            this.maid.getNavigation().moveTo(this.owner, this.speedModifier);
        }
    }
}
