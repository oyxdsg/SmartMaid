package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionBlockUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

/**
 * 攻击任务（M2）：锁定目标 → 追击 → 近战，目标死亡/丢失即完成。
 *
 * <p>目标模式（构造参数指定，优先级从高到低）：</p>
 * <ul>
 *     <li>指定实体（explicitTarget）：AI 显式给出目标实体</li>
 *     <li>指定类型（targetType）：打周围最近的该种生物（如猪/牛/羊，用于获取食物）</li>
 *     <li>自动：锁定周围最近敌对生物（Monster）</li>
 * </ul>
 */
public class AttackTask extends MaidAITask {

    /** 索敌范围（格） */
    private final int range;
    /** 显式指定目标（可选） */
    private final LivingEntity explicitTarget;
    /** 指定实体类型（可选）；非空时打最近的该种生物 */
    private final EntityType<?> targetType;
    /** 攻击冷却 tick */
    private static final int ATTACK_COOLDOWN = 20;

    private LivingEntity target;
    private int cooldown;

    public AttackTask(int range, LivingEntity explicitTarget, EntityType<?> targetType) {
        super("attack");
        this.range = range;
        this.explicitTarget = explicitTarget;
        this.targetType = targetType;
    }

    public AttackTask(int range, LivingEntity explicitTarget) {
        this(range, explicitTarget, null);
    }

    public AttackTask(int range, EntityType<?> targetType) {
        this(range, null, targetType);
    }

    public AttackTask(int range) {
        this(range, null, null);
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return true;
    }

    @Override
    public void start(SmartMaidEntity maid) {
        if (this.explicitTarget != null && this.explicitTarget.isAlive()) {
            this.target = this.explicitTarget;
        } else if (this.targetType != null) {
            this.target = PerceptionBlockUtil.findNearestByType(maid, this.range, this.targetType);
        } else {
            this.target = findNearestHostile(maid, this.range);
        }
        this.cooldown = 0;
        MaidDebug.log("Attack start target=" + (this.target == null ? "无"
                : this.target.getType().toShortString() + " @" + this.target.blockPosition())
                + (this.targetType != null ? " wantType=" + this.targetType.toShortString() : ""));
        if (this.target == null) {
            maid.showBubble(Component.literal("附近没有" + targetLabel()), 60);
        }
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        if (this.target == null || !this.target.isAlive()) {
            return; // isDone 捕获，任务结束
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
        return this.target == null || !this.target.isAlive();
    }

    @Override
    public String result() {
        if (this.target == null) {
            return this.targetType != null ? "附近没有" + targetLabel() : "无目标";
        }
        return !this.target.isAlive() ? "已击杀 " + this.target.getType().toShortString() : "目标丢失";
    }

    /** 目标类型的中文显示名；未指定类型时返回「敌人」。 */
    private String targetLabel() {
        return this.targetType != null ? this.targetType.getDescription().getString() : "敌人";
    }

    /** 找周围最近敌对生物（Monster，排除己方/玩家）。实现已收拢到感知工具 {@link PerceptionBlockUtil}。 */
    public static LivingEntity findNearestHostile(SmartMaidEntity maid, int range) {
        return PerceptionBlockUtil.findNearestHostile(maid, range);
    }
}
