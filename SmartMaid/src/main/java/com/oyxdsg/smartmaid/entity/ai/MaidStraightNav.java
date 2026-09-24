package com.oyxdsg.smartmaid.entity.ai;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 女仆"物理直线路径"降级导航（寻路兜底）。
 *
 * <p>触发：原版寻路失败 / 路径没真正到目标（被墙水阻断） / 绕远（节点超阈值）。
 * 激活后放弃 A*，改为**直线朝目标移动**（MoveControl 水平朝目标，高度由地形决定），
 * 途中遇到走不过去的障碍才停下来处理：</p>
 * <ul>
 *     <li>前方 2 格以上墙 → {@link MaidBlockBreaker} 挖墙脚，挖空后继续挖上方打通 2 格通道</li>
 *     <li>前方头顶被挡 → 挖上方</li>
 *     <li>前方 1 格高台阶/沟壁 → 直接 {@code jumpControl.jump()} 跳上去（不依赖 MoveControl 跳跃判定）</li>
 *     <li>前方脚下是深沟/水面（下方连续 2 格无支撑）→ {@link MaidBlockPlacer} 搭方块垫脚</li>
 *     <li>目标在正上方且高度差 >1（跳不上）→ <b>pillar 垫高</b>：起跳后在脚下放方块，落地站上去，
 *         循环直到高度差可跳（对齐玩家"在自己脚下放方块爬高"）</li>
 *     <li>1 格小落差/平地 → 直线走，MoveControl 自己下坡/平走</li>
 * </ul>
 *
 * <p>到达目标、长时间无进展、或遇到不可挖/无方块障碍时结束降级并恢复原版寻路（带冷却防抖动）。</p>
 */
public class MaidStraightNav {

    private static final int IDLE_LIMIT = 200;
    private static final double SPEED = 1.0D;
    /** 放弃（无方块/不可挖等）后冷却 tick，期间不再重新降级 */
    private static final int GIVE_UP_COOLDOWN = 60;
    /**
     * Pillar 起跳后放方块的时机（tick）：离线解表（tools/maid_jump_sim.js「pillar 垫高」段）。
     * vy0=0.42 时第 3 tick 脚底 y≈1.0013，刚离开站立格（原格空出）——此时放方块到脚下，
     * 落地即站在新方块上，高度 +1。飞行约 12 tick。
     */
    private static final int PILLAR_PLACE_TICK = 3;
    /** pillar 超时保护（tick）：一次起跳从起跳到落地的完整循环上限 */
    private static final int PILLAR_TIMEOUT = 60;

    private final MaidBlockBreaker breaker = new MaidBlockBreaker();
    private final MaidBlockPlacer placer = new MaidBlockPlacer();

    private BlockPos target;
    private int idleTicks;
    private BlockPos lastPos;
    private int giveUpTick = -1000;

    // pillar（垂直爬高）状态：目标在正上方且跳不上时，起跳后往脚下放方块垫高
    private boolean pillarJumped;   // 已起跳，等待放方块时机
    private BlockPos pillarCell;    // 本次要垫的格（起跳前的站立格）
    private int pillarTicks;        // 起跳后经过 tick（查表决定放方块时机）

    public boolean isActive() {
        return this.target != null;
    }

    public BlockPos target() {
        return this.target;
    }

    /** 激活直线降级，目标 pos */
    public void setTarget(BlockPos pos) {
        if (pos == null) {
            return;
        }
        this.target = pos.immutable();
        this.idleTicks = 0;
        this.lastPos = null;
        this.breaker.setMoveStraight(true);
        this.placer.setMoveStraight(true);
        // 搭路只消耗普通建材，不浪费贵重物品
        this.placer.setBridgeMode(true);
    }

    /** 清空降级状态并清理进行中动作的裂纹 */
    public void clear(SmartMaidEntity maid) {
        this.target = null;
        this.idleTicks = 0;
        this.lastPos = null;
        this.pillarJumped = false;
        this.pillarCell = null;
        if (maid != null) {
            this.breaker.abort(maid);
        }
        this.placer.abort();
    }

    /** 最近是否刚放弃过（触发侧冷却，防止立刻重新降级） */
    public boolean recentlyGaveUp(int currentTick) {
        return currentTick - this.giveUpTick < GIVE_UP_COOLDOWN;
    }

    /**
     * 每服务端 tick 驱动直线移动 + 破坏/搭路。
     *
     * @return true = 已到达或放弃（调用方应 {@link #clear(SmartMaidEntity)} 恢复原版寻路）
     */
    public boolean tick(SmartMaidEntity maid) {
        if (this.target == null) {
            return true;
        }
        if (maid.level().isClientSide()) {
            return false;
        }
        // 坐下 = 原地不动：立即结束降级（不挖不搭不走），恢复原版寻路交由统一下沉锁处理
        if (maid.isOrderedToSit()) {
            this.breaker.abort(maid);
            this.placer.abort();
            return true;
        }
        // 到达（水平 + 垂直 + 视线）
        if (isArrived(maid, this.target)) {
            MaidDebug.log("StraightNav 到达 " + this.target);
            this.breaker.abort(maid);
            return true;
        }
        // 优先完成当前挖掘/搭方块
        if (this.breaker.isActive()) {
            this.breaker.tick(maid);
            return false;
        }
        if (this.placer.isActive()) {
            this.placer.tick(maid);
            return false;
        }

        // 无进展检测（无进行中动作时累计）
        this.idleTicks++;
        BlockPos cp = maid.blockPosition();
        if (this.lastPos != null
                && (cp.distSqr(this.lastPos) > 4.0D || cp.getY() > this.lastPos.getY())) {
            this.idleTicks = 0;
        }
        this.lastPos = cp;
        if (this.idleTicks > IDLE_LIMIT) {
            return fail(maid, "无进展");
        }

        BlockPos from = maid.blockPosition();
        int dx = Integer.signum(this.target.getX() - from.getX());
        int dz = Integer.signum(this.target.getZ() - from.getZ());
        Level level = maid.level();

        // 垂直同柱（目标正上/正下，罕见）
        if (dx == 0 && dz == 0) {
            if (this.target.getY() > from.getY()) {
                BlockPos head = from.above();
                if (isSolid(level, head)) {
                    if (this.breaker.begin(maid, head)) {
                        MaidDebug.log("StraightNav 挖头顶 " + head);
                        return false;
                    }
                    return fail(maid, "头顶不可挖");
                }
                // 头顶净空：跳不上（高度差 >1）→ pillar 垫高爬升
                int dyUp = this.target.getY() - from.getY();
                if (dyUp > 1) {
                    return tickPillar(maid, from);
                }
                // 高度差 1 → 跳一下
                maid.getJumpControl().jump();
            }
            moveTo(maid, this.target.getX(), from.getY(), this.target.getZ());
            return false;
        }

        // 水平推进：检查前方 1 格的可通行性
        BlockPos ahead = from.offset(dx, 0, dz);
        BlockPos below = ahead.below();
        boolean aheadSolid = isSolid(level, ahead);
        boolean headSolid = isSolid(level, ahead.above());
        boolean belowSolid = isSolid(level, below);
        boolean below2Solid = isSolid(level, below.below());
        // 目标是否明显高于女仆（坑里/需爬升）→ 优先向上而不是水平穿墙
        boolean needUp = this.target.getY() - from.getY() > 1;

        // 1) 前方 2 格以上墙：
        //    需爬升（坑里）→ 挖上方方块，让头顶净空 → 触发"1 格台阶跳"逐格升高爬出坑；
        //    同高/向下 → 挖墙脚，水平打通通道
        if (aheadSolid && headSolid) {
            BlockPos dig = needUp ? ahead.above() : ahead;
            if (this.breaker.begin(maid, dig)) {
                MaidDebug.log("StraightNav " + (needUp ? "挖上方爬升 " : "挖 ") + dig
                        + " (目标 " + this.target + ")");
                return false;
            }
            return fail(maid, "墙不可挖");
        }
        // 2) 前方头顶被挡（脚格空但净空不足，坑壁上方/天花板）→ 挖上方
        if (headSolid) {
            if (this.breaker.begin(maid, ahead.above())) {
                MaidDebug.log("StraightNav 挖上方 " + ahead.above() + " (目标 " + this.target + ")");
                return false;
            }
            return fail(maid, "上方不可挖");
        }
        // 3) 前方 1 格高台阶/沟壁（脚实心头空）→ 直接跳上去（MoveControl 跳跃判定不可靠，自己触发）
        if (aheadSolid) {
            maid.getJumpControl().jump();
            moveTo(maid, ahead.getX(), from.getY(), ahead.getZ());
            return false;
        }
        // 4) 前方脚下是深沟/水面（下方连续 2 格无支撑）→ 搭方块垫脚
        if (!belowSolid && !below2Solid) {
            if (this.placer.begin(maid, below)) {
                MaidDebug.log("StraightNav 搭 " + below + " (目标 " + this.target + ")");
                return false;
            }
            return fail(maid, "搭路无方块");
        }
        // 5) 1 格小落差/平地/坡 → 直线走（MoveControl 自己下坡/上坡）

        // 直线朝目标移动（水平方向；高度保持当前，由地形决定，避免朝高处乱飞）
        moveTo(maid, this.target.getX(), from.getY(), this.target.getZ());
        return false;
    }

    /** MoveControl 直线走到 (x, y, z)（wantedY 保持当前高度，y 只做参考） */
    private void moveTo(SmartMaidEntity maid, double x, double y, double z) {
        maid.getMoveControl().setWantedPosition(x + 0.5D, y, z + 0.5D, SPEED);
    }

    /**
     * Pillar 垫高爬升（对齐玩家"在自己脚下放方块爬高"）：目标在正上方且高度差 >1（跳不上）。
     *
     * <p>离线解表（tools/maid_jump_sim.js「pillar 垫高」段）：vy0=0.42 起跳后
     * 第 {@link #PILLAR_PLACE_TICK} tick 脚底离地（原站立格空出），此时放方块到脚下，
     * 落地即站在新方块上，高度 +1；循环直到高度差可跳。运行时只查常量，不做物理计算。</p>
     *
     * @return true = pillar 进行中（调用方继续等）；false = pillar 失败（放弃降级）
     */
    private boolean tickPillar(SmartMaidEntity maid, BlockPos from) {
        // 1) 未起跳 → 备块 + 起跳
        if (!this.pillarJumped) {
            // 主手不是可垫方块 → 从背包换一个（只耗普通建材）
            if (!MaidActions.isBridgeBlock(maid.getMainHandItem())) {
                if (!MaidActions.equipBridgeBlockFromBackpack(maid)) {
                    return fail(maid, "pillar 无方块");
                }
            }
            // 停下水平移动（否则 MoveControl 会把女仆水平拉走，离开垫块点）
            maid.getNavigation().stop();
            maid.getMoveControl().setWait();
            this.pillarJumped = true;
            this.pillarCell = from.immutable();
            this.pillarTicks = 0;
            maid.getJumpControl().jump();
            MaidDebug.log("StraightNav pillar 起跳 @" + this.pillarCell);
            return true;
        }

        this.pillarTicks++;
        // 2) 起跳后第 PILLAR_PLACE_TICK tick（脚底离地、原格空出）→ 放方块到脚下
        if (this.pillarTicks == PILLAR_PLACE_TICK) {
            // 点站立格下方 UP 面 → 方块落到站立格
            if (MaidActions.placeBlock(maid, this.pillarCell.below(), Direction.UP)) {
                MaidDebug.log("StraightNav pillar 垫 @" + this.pillarCell + " (y=" + this.pillarCell.getY() + ")");
            }
        }
        // 3) 落地：站在垫高的方块上（脚下变实心）→ 本层完成
        if (maid.onGround() && isSolid(maid.level(), this.pillarCell)) {
            MaidDebug.log("StraightNav pillar 上一层 -> y=" + maid.blockPosition().getY());
            this.pillarJumped = false;
            this.pillarCell = null;
            return true;
        }
        // 超时保护：起跳 60 tick 还没放上/落地（可能被卡）→ 放弃
        if (this.pillarTicks > PILLAR_TIMEOUT) {
            this.pillarJumped = false;
            this.pillarCell = null;
            return fail(maid, "pillar 超时");
        }
        return true;
    }

    /** 结束降级并记录放弃时刻（触发侧冷却），清裂纹 */
    private boolean fail(SmartMaidEntity maid, String reason) {
        this.giveUpTick = maid.tickCount;
        MaidDebug.log("StraightNav 放弃(" + reason + ") " + this.target);
        this.breaker.abort(maid);
        return true;
    }

    /** 是否已到达目标（水平 2 格内 + 垂直 1.5 格内 + 视线不被墙隔开） */
    private boolean isArrived(SmartMaidEntity maid, BlockPos target) {
        double dx = maid.getX() - (target.getX() + 0.5D);
        double dy = maid.getY() - (target.getY() + 0.5D);
        double dz = maid.getZ() - (target.getZ() + 0.5D);
        if (dx * dx + dz * dz > 4.0D || dy * dy > 2.25D) {
            return false;
        }
        // 距离近但隔墙（目标在墙另一侧）→ 视线被挡视为未到达
        return maid.level().clip(new ClipContext(
                maid.getEyePosition(), Vec3.atCenterOf(target),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, maid)).getType() != HitResult.Type.BLOCK;
    }

    /** 有完整碰撞体（挡路/可站立）；空气、水、草丛、花等返回 false */
    private boolean isSolid(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
    }
}
