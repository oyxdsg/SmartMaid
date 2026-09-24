package com.oyxdsg.smartmaid.entity.ai.combat;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.MaidMoveControl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 战斗走位（见 DEVELOPMENT_COMBAT.md §5.1）。
 *
 * <p>核心：用原版 {@code MoveControl.strafe()} 走位——其 STRAFE 分支移动时**不调用
 * {@code setYRot}**，因此可以在「面向敌人」的同时后退 / 侧移（用 {@code setWantedPosition}
 * 会转向移动方向 = 转身，禁止）。</p>
 *
 * <ul>
 *     <li>保持距离：按距离误差得到前后分量（>期望距离前进，<期望距离后退）</li>
 *     <li>背对跳：后退时身后 ≤1 格高障碍且头顶净空 → {@code jumpControl.jump()}，与原版玩家一致</li>
 *     <li>绕行（不可省略）：身后被挡且不可跳 → 侧移绕行</li>
 * </ul>
 */
public final class MaidCombatMovement {

    /** 侧移方向（+1 右 / -1 左） */
    private float side = 1.0F;
    /** 切换侧移方向的冷却，避免左右抖动 */
    private int sideSwitchCooldown;
    /** 连续"后退受阻"tick 数（诊断/绕行触发用） */
    private int stuckTicks;
    /** 走位状态日志节流（每 20 tick 一条） */
    private int lastStateLogTick = -100;

    /** 走位速度倍率（1.0 = 与正常行走同速；原版 strafe 固定 0.25 太慢） */
    private static final double STRAFE_SPEED = 1.0D;
    /** 距离死区（格）：在此范围内不再前后微调，避免原地抖动 */
    private static final double DISTANCE_DEAD_ZONE = 0.3D;
    /** 绕行方向切换冷却（tick） */
    private static final int DETOUR_COOLDOWN = 20;

    /**
     * 走位一个 tick（死区取对称：{@code wantedDistance ± DISTANCE_DEAD_ZONE}，供进食/远程保持距离用）。
     *
     * @param wantedDistance 与目标期望保持的距离（格）
     */
    public void keepDistance(SmartMaidEntity maid, LivingEntity target, double wantedDistance) {
        this.keepDistance(maid, target, wantedDistance, wantedDistance + DISTANCE_DEAD_ZONE);
    }

    /**
     * 走位一个 tick。
     *
     * @param wantedDistance 与目标期望保持的距离（格），死区下限 = {@code wantedDistance - DISTANCE_DEAD_ZONE}
     * @param maxDistance    死区上限（格）：距离超过此值才前进拉近——近战传攻击距离
     *                       {@link SmartMaidEntity#MAID_ATTACK_REACH}（3.2），
     *                       超过攻击距离却停在死区会"够不着打不到"
     */
    public void keepDistance(SmartMaidEntity maid, LivingEntity target, double wantedDistance, double maxDistance) {
        double dx = target.getX() - maid.getX();
        double dz = target.getZ() - maid.getZ();
        double dist = Math.max(1.0E-4D, Math.sqrt(dx * dx + dz * dz));

        // 面向敌人：MC 前向 = (-sin(yaw), cos(yaw))，令其指向 (dx, dz)
        maid.setYRot((float) Math.toDegrees(Mth.atan2(-dx, dz)));

        // 后退用满幅 -1（STRAFE 的 zza 直接取该值，比例值会导致后退被按比例减速）；
        // 前进按距离误差缓动；死区内（下限~上限）不动
        float forward;
        if (dist < wantedDistance - DISTANCE_DEAD_ZONE) {
            forward = -1.0F;
        } else if (dist > maxDistance) {
            forward = (float) Mth.clamp((dist - wantedDistance) * 0.5D, 0.0D, 1.0D);
        } else {
            forward = 0.0F;
        }

        // 默认不侧移（避免一直绕圈）：只有后退被挡、且背对跳也上不去时才侧移绕行
        float side = 0.0F;
        if (forward < 0.0F && maid.horizontalCollision) {
            boolean jumped = tryBackJump(maid, dx, dz, dist);
            if (jumped) {
                this.stuckTicks = 0;
            } else {
                this.stuckTicks++;
                if (this.stuckTicks % 40 == 0) {
                    MaidDebug.log("Combat 后退受阻 " + this.stuckTicks + "t（墙角？尝试绕行）");
                }
                if (this.sideSwitchCooldown <= 0) {
                    this.side = pickOpenSide(maid);
                    this.sideSwitchCooldown = DETOUR_COOLDOWN;
                    MaidDebug.log("Combat detour 绕行 side=" + this.side + " (stuck=" + this.stuckTicks + "t)");
                }
                side = this.side;
            }
        } else {
            this.stuckTicks = 0;
        }
        if (this.sideSwitchCooldown > 0) {
            this.sideSwitchCooldown--;
        }

        if (maid.getMoveControl() instanceof MaidMoveControl moveControl) {
            moveControl.combatStrafe(forward, side, STRAFE_SPEED);
        } else {
            maid.getMoveControl().strafe(forward, side);
        }

        if (maid.tickCount - this.lastStateLogTick >= 20) {
            this.lastStateLogTick = maid.tickCount;
            MaidDebug.log("Combat strafe dist=" + String.format("%.1f", dist)
                    + " fwd=" + String.format("%.2f", forward)
                    + " side=" + String.format("%.1f", side)
                    + (forward < 0.0F ? " (后退/面向敌人)" : ""));
        }
    }

    /**
     * 背对跳：面向敌人后退时，若身后有一格高（≤1.0 格顶面）障碍且头顶净空，
     * 则起跳（物理为原版 {@code jumpFromGround}），空中保持后退输入可跳上障碍。
     *
     * @return true 表示已起跳（或本 tick 不需要起跳）
     */
    private boolean tryBackJump(SmartMaidEntity maid, double dx, double dz, double dist) {
        if (!maid.onGround() || !maid.horizontalCollision) {
            return true;
        }
        int ox = (int) Math.round(-dx / dist);
        int oz = (int) Math.round(-dz / dist);
        if (ox == 0 && oz == 0) {
            return true;
        }
        BlockPos foot = maid.blockPosition().offset(ox, 0, oz);
        BlockPos head = foot.above();
        VoxelShape shape = maid.level().getBlockState(foot).getCollisionShape(maid.level(), foot);
        if (shape.isEmpty()) {
            return true;
        }
        if (!maid.level().getBlockState(head).getCollisionShape(maid.level(), head).isEmpty()) {
            return true;
        }
        double topY = shape.max(Direction.Axis.Y) + foot.getY();
        if (topY <= maid.getY() + 1.0D) {
            MaidDebug.log("Combat back-jump 背对跳 -> " + foot);
            maid.getJumpControl().jump();
            return true;
        }
        return false;
    }

    /** 选一个可通行的侧移方向（优先保持当前方向）。 */
    private float pickOpenSide(SmartMaidEntity maid) {
        double yaw = Math.toRadians(maid.getYRot());
        double rx = Math.cos(yaw);
        double rz = Math.sin(yaw);
        boolean rightOpen = isPassable(maid, maid.blockPosition().offset((int) Math.round(rx), 0, (int) Math.round(rz)));
        boolean leftOpen = isPassable(maid, maid.blockPosition().offset((int) Math.round(-rx), 0, (int) Math.round(-rz)));
        if (rightOpen && !leftOpen) {
            return 1.0F;
        }
        if (leftOpen && !rightOpen) {
            return -1.0F;
        }
        // 两侧都通或都堵 → 交替（避免墙角两边都堵时永远只试同一侧）
        return this.side >= 0.0F ? -1.0F : 1.0F;
    }

    private boolean isPassable(SmartMaidEntity maid, BlockPos pos) {
        BlockState foot = maid.level().getBlockState(pos);
        BlockState head = maid.level().getBlockState(pos.above());
        return foot.getCollisionShape(maid.level(), pos).isEmpty()
                && head.getCollisionShape(maid.level(), pos.above()).isEmpty();
    }
}
