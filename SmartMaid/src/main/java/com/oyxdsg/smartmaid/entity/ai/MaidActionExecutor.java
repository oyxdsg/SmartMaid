package com.oyxdsg.smartmaid.entity.ai;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 女仆动作执行器：处理原版导航/移动控制无法精确覆盖的"跳越"动作。
 *
 * <p><b>查表执行（模组不做物理计算）</b>：障碍场景（水平 feet 位移 dist × 高度差 dy）
 * 的起跳解 {@code {sprint, back}} 由 {@link MaidJumpTable} 离线预计算（tools/gen_jump_table.js），
 * 运行时直接查表：</p>
 * <ol>
 *     <li>查询起跳解 → 确定走/跑档位、起跳点后撤量 back</li>
 *     <li>起跳点 = 当前格中心朝落点偏移 (0.5 - 边缘内缩 - back)，朝向对准落点格中心</li>
 *     <li>MoveControl 自驱动走到起跳点 → jumpFromGround 起跳（继承地面速度，疾跑 +0.2 冲量）</li>
 *     <li>空中每 tick 保持前进输入（+0.02 维持速度），落地后恢复原版移动</li>
 * </ol>
 *
 * <p>物理全交原版（jumpFromGround 起跳、重力/摩擦/碰撞），只在起跳瞬间接管控制，不覆盖 {@code travel}。</p>
 */
public class MaidActionExecutor {

    private enum State { IDLE, POSITIONING, JUMP, COOLDOWN }

    private static final double EDGE_INSET = 0.1D;       // 起跳格边缘内缩（留出起跳距离）
    private static final double EDGE_REACH = 0.25D;      // 判定到达起跳点的距离
    private static final double MIN_H_DIST = 2.0D;       // 水平 >=2 格才需要精确跳越
    private static final double MAX_H_DIST = 4.0D;       // 跳跃极限
    private static final int MAX_AIR_TICKS = 30;         // 飞行超时兜底
    private static final int MAX_POSITIONING_TICKS = 60; // 定位超时兜底

    private final SmartMaidEntity maid;

    private State state = State.IDLE;
    private Vec3 edgePos = Vec3.ZERO;
    private double yawRad = 0.0D;
    private boolean sprint = false;
    private int airTicks = 0;
    private int positioningTicks = 0;

    public MaidActionExecutor(SmartMaidEntity maid) {
        this.maid = maid;
    }

    public boolean active() {
        return this.state != State.IDLE;
    }

    /** 起跳瞬间是否由执行器接管移动控制（MoveControl 据此让路） */
    public boolean jumping() {
        return this.state == State.JUMP;
    }

    /**
     * 尝试处理一次"从当前格到目标格"的跳越（查表拿精确解）。
     *
     * @return true 表示已接管本动作
     */
    public boolean tryHandle(BlockPos from, BlockPos to) {
        if (this.state != State.IDLE || from.equals(to)) {
            return false;
        }
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);

        if (hDist < MIN_H_DIST || hDist > MAX_H_DIST) {
            return false;
        }
        // 高度差：只能上 1 格 / 平跳 / 下 3 格
        if (dy > 1 || dy < -3) {
            return false;
        }
        if (!this.isJumpable(from, to)) {
            return false;
        }

        // 查离线精确解表；无解 → 本场景不可达，放弃接管
        int dist = (int) Math.round(hDist);
        MaidJumpTable.JumpSolution sol = MaidJumpTable.get(dist, dy);
        if (sol == null) {
            return false;
        }
        this.sprint = sol.sprint();
        this.start(from, to, dx / hDist, dz / hDist, sol.back());
        return true;
    }

    /** 每 tick 驱动（由 SmartMaidEntity.aiStep 调用） */
    public void tick() {
        // 坐下 = 原地不动：不执行任何跳跃（含跨沟/上台阶）
        if (this.maid.isOrderedToSit()) {
            return;
        }
        switch (this.state) {
            case POSITIONING -> this.tickPositioning();
            case JUMP -> this.tickJump();
            case COOLDOWN -> this.tickCooldown();
            default -> {
            }
        }
    }

    private void start(BlockPos from, BlockPos to, double nx, double nz, double back) {
        this.state = State.POSITIONING;
        this.positioningTicks = 0;

        // 起跳点：当前格中心，朝落点方向偏移 (0.5 - EDGE_INSET - back)（边缘内缩留起跳距离，back 为后撤）
        double offset = (0.5 - EDGE_INSET) - back;
        this.edgePos = new Vec3(
                from.getX() + 0.5 + nx * offset,
                from.getY() + 0.5,
                from.getZ() + 0.5 + nz * offset);

        // 朝向始终对准落点格中心（MC 前向 = (-sin(yaw),0,cos(yaw)) → yaw = atan2(-tx, tz)）
        double tx = to.getX() + 0.5 - this.edgePos.x;
        double tz = to.getZ() + 0.5 - this.edgePos.z;
        this.yawRad = Mth.atan2(-tx, tz);

        // 清掉原导航路径，用 MoveControl 自驱动精确走向起跳点
        this.maid.getNavigation().stop();
        this.maid.getMoveControl().setWantedPosition(this.edgePos.x, this.edgePos.y, this.edgePos.z,
                this.sprint ? 1.3D : 1.0D);
        this.maid.setSprinting(this.sprint);
        MaidDebug.log("Jump start " + from + " -> " + to
                + " sprint=" + this.sprint
                + " back=" + MaidDebug.fmt1(back)
                + " takeoff=" + MaidDebug.fmt1(JumpPhysics.takeoffSpeed(
                        this.maid.getAttributeValue(Attributes.MOVEMENT_SPEED), this.sprint))
                + " ticks=" + JumpPhysics.flightTicks());
    }

    private void tickPositioning() {
        this.positioningTicks++;
        Vec3 pos = this.maid.position();
        double d = Math.sqrt((pos.x - this.edgePos.x) * (pos.x - this.edgePos.x)
                + (pos.z - this.edgePos.z) * (pos.z - this.edgePos.z));

        // 持续驱动走向起跳点（防止 wanted 被其它逻辑覆盖）
        this.maid.getMoveControl().setWantedPosition(this.edgePos.x, this.edgePos.y, this.edgePos.z,
                this.sprint ? 1.3D : 1.0D);

        if ((d <= EDGE_REACH && this.maid.onGround()) || this.positioningTicks > MAX_POSITIONING_TICKS) {
            this.doJump();
        }
    }

    private void doJump() {
        this.state = State.JUMP;
        this.airTicks = 0;

        // 转身正对落点（sprint 冲刺冲量沿此方向）
        this.maid.setYRot((float) Math.toDegrees(this.yawRad));

        // 起跳：原版 jumpFromGround（vy=0.42，最大抬升 1.25 格）
        this.maid.getJumpControl().jump();

        // 关键：起跳瞬间施加"离线解表"算好的水平速度（takeoff，沿落点方向）。
        // 若只继承当前 deltaMovement，女仆走到起跳点时会减速（MoveControl 接近目标减速），
        // 起跳速度不足导致落点偏短掉沟——玩家跑跳是"全速冲过起跳点"，故在此注入精确速度。
        double takeoff = JumpPhysics.takeoffSpeed(
                this.maid.getAttributeValue(Attributes.MOVEMENT_SPEED), this.sprint);
        Vec3 vel = this.maid.getDeltaMovement();
        this.maid.setDeltaMovement(
                -Math.sin(this.yawRad) * takeoff,
                vel.y,
                Math.cos(this.yawRad) * takeoff);

        this.maid.getNavigation().stop();
        MaidDebug.log("Jump fired yaw=" + (int) Math.toDegrees(this.yawRad)
                + " sprint=" + this.sprint
                + " takeoff=" + MaidDebug.fmt1(takeoff)
                + " vel=" + this.maid.getDeltaMovement());
    }

    private void tickJump() {
        this.airTicks++;
        // 空中持续前进输入：applyInput 每 tick 把 zza ×0.98，重置为 >1 保证
        // travelVector 归一化满速，moveRelative 持续 +0.02 维持速度（否则纯惯性落点过短）
        this.maid.setZza(2.0F);
        this.maid.setXxa(0.0F);

        if (this.maid.onGround() || this.airTicks >= MAX_AIR_TICKS) {
            this.state = State.COOLDOWN;
            MaidDebug.log("Jump landed at " + this.maid.blockPosition()
                    + (this.maid.onGround() ? "" : " (airborne timeout)"));
        }
    }

    private void tickCooldown() {
        this.maid.setSprinting(false);
        this.state = State.IDLE;
    }

    /**
     * 判定 from→to 之间是可跳跃跨越的障碍：
     * <ul>
     *     <li>沟：中间格为空气且无支撑</li>
     *     <li>实心障碍：中间格为实心方块，但 ≤1 格高（上方为空）——可跳上/越过</li>
     * </ul>
     * 中间有支撑（平地）或 ≥2 格高墙 → 非跳跃场景，返回 false。
     * 落点脚下必须有实体支撑。
     */
    private boolean isJumpable(BlockPos from, BlockPos to) {
        int sx = Integer.signum(to.getX() - from.getX());
        int sz = Integer.signum(to.getZ() - from.getZ());
        int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
        boolean hasGapOrObstacle = false;
        BlockPos cursor = from;
        for (int i = 1; i < steps; i++) {
            cursor = cursor.offset(sx, 0, sz);
            BlockState body = this.maid.level().getBlockState(cursor);
            BlockState below = this.maid.level().getBlockState(cursor.below());
            if (body.isAir() && below.isAir()) {
                hasGapOrObstacle = true;      // 沟
            } else if (body.isAir()) {
                return false;                 // 中间有支撑（平地），不是跳跃场景
            } else {
                BlockState above = this.maid.level().getBlockState(cursor.above());
                if (!above.isAir()) {
                    return false;             // ≥2 格高墙，跳不上
                }
                hasGapOrObstacle = true;      // 1 格高实心障碍，可跳跃跨越
            }
        }
        if (!hasGapOrObstacle) {
            return false;
        }
        return !this.maid.level().getBlockState(to.below()).isAir();
    }
}
