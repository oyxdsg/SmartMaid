/*
 * SmartMaid —— MIT License, Copyright (c) 2026 oyxdsg
 *
 * 本文件的“触发跳跃”判断条件改编自 TouhouLittleMaid（车万女仆）的 MaidMoveControl。
 * TouhouLittleMaid 代码部分以 MIT 许可发布：
 *   Copyright (c) 2019-2025 tartaric_acid
 *   https://github.com/TartaricAcid/TouhouLittleMaid
 * 完整第三方声明见仓库根目录 NOTICE.md。
 */
package com.oyxdsg.smartmaid.entity.ai;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 女仆移动控制：完全沿用原版 {@link MoveControl} 的移动体系（转向 + setSpeed + travel 物理），
 * 仅参照车万女仆（TouhouLittleMaid MaidMoveControl）优化"触发跳跃"的判断条件。
 *
 * <p>跳跃触发（满足其一即 {@code jumpControl.jump()}，跳跃物理为原版 jumpFromGround）：</p>
 * <ul>
 *     <li>目标点 Y 差大于跨步高度(0.6) 且水平距离足够近（前方要抬 1 格 → 跳上 1 格方块/台阶）</li>
 *     <li>脚下方块有碰撞体、女仆头顶低于该方块顶面、且该方块不在跳跃黑名单（门/栅栏/可攀爬）→ 跳起避免卡住</li>
 * </ul>
 */
public class MaidMoveControl extends MoveControl {

    private final SmartMaidEntity maid;

    public MaidMoveControl(SmartMaidEntity maid) {
        super(maid);
        this.maid = maid;
    }

    @Override
    public void tick() {        // 执行器起跳瞬间接管：本 tick 让路（物理由原版执行）
        if (this.maid.getActionExecutor().jumping()) {
            return;
        }
        if (this.operation == Operation.MOVE_TO) {
            this.operation = Operation.WAIT;

            double dx = this.wantedX - this.mob.getX();
            double dy = this.wantedY - this.mob.getY();
            double dz = this.wantedZ - this.mob.getZ();
            double distSqr = dx * dx + dy * dy + dz * dz;
            if (distSqr < 2.5E-7D) {
                this.mob.setZza(0.0F);
                return;
            }

            // 跨沟/跳越：前方目标格需精确跳越时交给执行器接管。
            // 直线降级模式（straightNav 激活）下跳过——移动/挖/搭由 straightNav 自己判定，
            // 避免跨沟执行器与降级逻辑打架（女仆该挖墙却去跳）。
            BlockPos from = this.mob.blockPosition();
            BlockPos to = new BlockPos(Mth.floor(this.wantedX), Mth.floor(this.wantedY), Mth.floor(this.wantedZ));
            if (!this.straightNavActive() && this.maid.getActionExecutor().tryHandle(from, to)) {
                return;
            }

            // 转向（车万女仆用 90 度上限，比原版 30 度更灵活）
            float angle = (float) Math.toDegrees(Mth.atan2(dz, dx)) - 90.0F;
            this.mob.setYRot(this.rotlerp(this.mob.getYRot(), angle, 90.0F));

            // 速度（原版：speedModifier × MOVEMENT_SPEED 属性）
            this.mob.setSpeed((float) (this.speedModifier
                    * this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED)));
            // 关键：显式 setZza(1) 提供方向输入。Mob.setSpeed 会自动 setZza(speed)，
            // 但 moveRelative 的 getInputVector(travelVector, speed, yaw) 会再 scale(speed)，
            // 若 zza=speed 则加速度变成 speed²（平方衰减=蜗牛）；设为 1 才能得到线性 accel=speed。
            this.mob.setZza(1.0F);
            this.mob.setXxa(0.0F);

            // 跳跃触发（车万女仆逻辑）
            BlockPos blockPos = this.mob.blockPosition();
            BlockState blockState = this.mob.level().getBlockState(blockPos);
            VoxelShape voxelShape = blockState.getCollisionShape(this.mob.level(), blockPos);

            boolean needJump = (this.mob.maxUpStep() < dy && dx * dx + dz * dz < Math.max(1.0F, this.mob.getBbWidth()))
                    || (!voxelShape.isEmpty()
                        && this.mob.getY() < (voxelShape.max(Direction.Axis.Y) + blockPos.getY())
                        && !blockState.is(BlockTags.DOORS)
                        && !blockState.is(BlockTags.FENCES)
                        && !blockState.is(BlockTags.CLIMBABLE));

            if (needJump) {
                this.mob.getJumpControl().jump();
                this.operation = Operation.JUMPING;
            }
        } else if (this.operation == Operation.JUMPING && this.mob.isInWater()) {
            this.operation = Operation.WAIT;
        } else {
            super.tick();
        }
    }

    /** 是否处于直线降级模式（此时移动/挖/搭由 MaidStraightNav 接管） */
    private boolean straightNavActive() {
        return this.maid.getNavigation() instanceof MaidGroundPathNavigation nav
                && nav.getStraightNav().isActive();
    }

    /**
     * 战斗走位原语：原版 {@link MoveControl#strafe} 会把速度倍率固定为 0.25（偏慢），
     * 这里允许指定倍率，供「面向敌人后退 / 侧移绕行 / 保持距离」使用（STRAFE 分支不转向）。
     */
    public void combatStrafe(float forward, float right, double speedModifier) {
        super.strafe(forward, right);
        this.speedModifier = speedModifier;
    }
}
