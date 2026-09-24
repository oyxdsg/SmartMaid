package com.oyxdsg.smartmaid.entity.ai;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 女仆挖掘执行器：把「接近目标（导航/挖挡路块/搭高）→ 有挖掘过程的破坏（裂纹 + 按硬度耗时）→ 掉落进背包」
 * 抽成独立状态机，供挖矿任务、收割任务、以及寻路降级（直线前进遇障碍挖掉）复用。
 *
 * <p><b>对齐玩家挖掘</b>：玩家挖的是「准星命中的第一个方块」——目标被其它方块挡住时，
 * 玩家会先挖掉挡路块；目标够不着（太高）时会搭方块爬上去。本执行器同一逻辑，决策顺序：</p>
 * <ul>
 *     <li>目标在女仆上方且垂直够不着 → <b>pillar 搭高</b>（最高优先级，先于挖挡路块；
 *         原地搭高，离线解表起跳后第 3 tick 放脚下方块，搭到够得着再挖；搭高后水平仍远由导航横向接近）</li>
 *     <li>水平太远 → 导航接近（原版 A* / {@link MaidGroundPathNavigation} 直线降级）</li>
 *     <li>视线被非目标方块挡住 → <b>临时切目标为挡路块</b>（含脚下土，挖土往下找埋地矿），挖掉后恢复真正目标</li>
 *     <li>能直接挖 → 裂纹 + 耗时破坏</li>
 * </ul>
 *
 * <p>用法：{@code begin(maid, pos)} 开始（方块已空/不可破坏返回 false）；之后每服务端 tick
 * 调 {@code tick(maid)}；返回 true 表示真正目标被破坏完成。中断用 {@code abort(maid)} 清裂纹。</p>
 */
public class MaidBlockBreaker {

    /** 挖掘挥动周期（tick）：按固定间隔播放完整挥动动画，模拟玩家持续挖掘 */
    private static final int SWING_INTERVAL = 5;
    private static final int PILLAR_TIMEOUT = 60;
    /** 垂直可挖范围上限（相对女仆身体中心）：头顶两格 */
    private static final double VERT_HI = 2.5D;

    /** 真正目标（矿/要挖的方块）；挖挡路块时与 {@link #target} 不同 */
    private BlockPos goal;
    /** 当前实际在挖的方块（= goal 或挡路块） */
    private BlockPos target;
    private int digTicks;
    private int digTotal;

    /** 直线接近模式（寻路降级用）：不再用原版 A* 导航，直接朝目标走 */
    private boolean moveStraight;

    // pillar（搭高）状态
    private boolean pillarJumped;
    private BlockPos pillarCell;
    private int pillarTicks;
    /** 起跳前脚底 y（落地高度检测基准） */
    private double pillarStartY;
    /** 本次搭高连续失败次数（放块失败重试，超限放弃；每上一层成功清零） */
    private int pillarAttempts;

    public void setMoveStraight(boolean moveStraight) {
        this.moveStraight = moveStraight;
    }

    public boolean isActive() {
        return this.target != null;
    }

    public BlockPos target() {
        return this.target;
    }

    /** 开始挖 pos；返回 false = 方块已空 / 不可破坏（computeMiningTicks < 0） */
    public boolean begin(SmartMaidEntity maid, BlockPos pos) {
        if (maid.level().isClientSide()) {
            return false;
        }
        BlockState state = maid.level().getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        this.goal = pos.immutable();
        return beginDig(maid, pos);
    }

    /** 开始挖具体方块（goal/target 已就位） */
    private boolean beginDig(SmartMaidEntity maid, BlockPos pos) {
        BlockState state = maid.level().getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        // 挖掘前自动换最优工具（对齐玩家判定：正确工具才有掉落、耗时更短）
        MaidActions.equipBestToolFor(maid, state);
        int total = MaidActions.computeMiningTicks(maid, pos);
        if (total < 0) {
            return false;
        }
        this.target = pos.immutable();
        this.digTicks = 0;
        this.digTotal = total;
        maid.swing(InteractionHand.MAIN_HAND);
        MaidDebug.log("Breaker begin " + this.target + " (" + total + "t)");
        return true;
    }

    /**
     * 每服务端 tick 驱动：接近目标（导航/挖挡路块/搭高）→ 走到旁 → 挥动 + 裂纹 → 耗时至破坏。
     *
     * <p><b>决策顺序（对齐玩家）</b>：先水平接近 → 挖开视线里的挡路块（含脚下土，往下挖矿）→
     * 目标在上方且够不着才 pillar 搭高 → 能直接挖就挖。</p>
     *
     * @return true = 真正目标（goal）完成破坏（breaker 已复位）
     */
    public boolean tick(SmartMaidEntity maid) {
        if (this.target == null) {
            return false;
        }
        if (maid.level().isClientSide()) {
            return false;
        }

        // 挖掘进行中（digTicks > 0）→ 持续挖完当前目标，不被 pillar/挡路块/导航打断：
        //   搭高后站在柱子上挖矿，若每 tick 重新决策，可能因"够不着/命中柱子"被反复打断。
        if (this.digTicks > 0) {
            // 目标已被挖掉 → 完成本块（挡路块则切回真正目标）
            if (maid.level().getBlockState(this.target).isAir()) {
                if (!this.goal.equals(this.target)) {
                    if (maid.level().getBlockState(this.goal).isAir()) {
                        this.clear();
                        return true;
                    }
                    if (beginDig(maid, this.goal)) {
                        return false;
                    }
                    this.clear();
                    return true;
                }
                this.clear();
                return true;
            }
            // 继续推进挖掘
            maid.getLookControl().setLookAt(this.target.getX() + 0.5D,
                    this.target.getY() + 0.5D, this.target.getZ() + 0.5D);
            this.digTicks++;
            if (this.digTicks % SWING_INTERVAL == 0) {
                maid.swing(InteractionHand.MAIN_HAND);
            }
            if (MaidDebug.verbose() && this.digTicks % 10 == 0) {
                MaidDebug.log("Breaker 挖掘中 " + this.target + " " + this.digTicks + "/" + this.digTotal);
            }
            if (this.digTicks >= this.digTotal) {
                MaidActions.showMiningProgress(maid, this.target, -1);
                boolean ok = MaidActions.breakBlock(maid, this.target);
                // 若刚挖的是挡路块 → 保持已挖空状态，下一 tick 切回真正目标
                if (!this.goal.equals(this.target)) {
                    return false;
                }
                this.clear();
                return ok;
            }
            int stage = this.digTicks * 10 / this.digTotal;
            MaidActions.showMiningProgress(maid, this.target, Math.min(9, stage));
            return false;
        }

        // 1) 当前目标（可能是挡路块）已被挖掉
        if (maid.level().getBlockState(this.target).isAir()) {
            // 若挖的是挡路块 → 切回真正目标
            if (!this.goal.equals(this.target)) {
                if (maid.level().getBlockState(this.goal).isAir()) {
                    // 真正目标也被挖掉（或本来就没）→ 完成
                    this.clear();
                    return true;
                }
                if (beginDig(maid, this.goal)) {
                    return false;
                }
                this.clear();
                return true;
            }
            this.clear();
            return true;
        }

        // 2) 目标在女仆上方且垂直够不着 → 需要 pillar（最高优先级，先于挖挡路块）：
        //    矿放高处/山丘上时玩家会搭方块上去挖，而不是挖面前的土。
        if (needPillar(maid, this.target)) {
            // 2a) 水平还远 → 先导航到矿附近（原版 A* + 直线降级会自动挖墙/搭高把女仆带过去），
            //     够近后再原地 pillar。否则原地搭高永远够不到斜上方的矿（会搭空/超时掉落）。
            if (!isHorizReach(maid, this.target)) {
                if (this.moveStraight) {
                    maid.getMoveControl().setWantedPosition(
                            this.target.getX() + 0.5D, this.target.getY(), this.target.getZ() + 0.5D, 1.0D);
                } else if (maid.getNavigation().isDone() || maid.tickCount % 20 == 0) {
                    MaidActions.navigateTo(maid, this.target, 1.0D);
                }
                return false;
            }
            // 2b) 水平已够近 → 原地 pillar 搭高
            if (pillar(maid)) {
                return false;
            }
            // pillar 失败（无方块/超时）→ 放弃（未完成，调用方据 isActive=false 换目标）
            MaidDebug.log("Breaker 无法搭高到 " + this.target + "，放弃");
            this.clear();
            return false;
        }

        // 3) 水平太远 → 导航接近（原版 A* 或寻路降级挖墙/搭高）
        if (!isHorizReach(maid, this.target)) {
            if (this.moveStraight) {
                maid.getMoveControl().setWantedPosition(
                        this.target.getX() + 0.5D, this.target.getY(), this.target.getZ() + 0.5D, 1.0D);
            } else if (maid.getNavigation().isDone() || maid.tickCount % 20 == 0) {
                MaidActions.navigateTo(maid, this.target, 1.0D);
            }
            return false;
        }

        // 4) 视线被非目标方块挡住 → 临时挖挡路块（对齐玩家：准星命中哪个挖哪个）。
        //    含矿埋地下的场景：射线从眼睛向下命中脚下的土 → 挖土往下走，直到命中矿本身。
        BlockPos hit = MaidActions.lineHit(maid, this.target);
        if (hit != null && !hit.equals(this.target)) {
            if (beginDig(maid, hit)) {
                MaidDebug.log("Breaker 挖挡路块 " + hit + " (目标 " + this.target + ")");
                return false;
            }
            // 挡路块不可挖（基岩等）→ 放弃这个目标
            MaidDebug.log("Breaker 挡路块不可挖 " + hit + "，放弃目标 " + this.target);
            this.clear();
            return false;
        }

        // 5) 能直接挖 → 停导航，面向目标，推进挖掘
        maid.getNavigation().stop();
        maid.getLookControl().setLookAt(this.target.getX() + 0.5D,
                this.target.getY() + 0.5D, this.target.getZ() + 0.5D);
        this.digTicks++;
        if (this.digTicks % SWING_INTERVAL == 0) {
            maid.swing(InteractionHand.MAIN_HAND);
        }
        if (MaidDebug.verbose() && this.digTicks % 10 == 0) {
            MaidDebug.log("Breaker 挖掘中 " + this.target + " " + this.digTicks + "/" + this.digTotal);
        }
        if (this.digTicks >= this.digTotal) {
            MaidActions.showMiningProgress(maid, this.target, -1);
            boolean ok = MaidActions.breakBlock(maid, this.target);
            // 若刚挖的是挡路块 → **不清空 target**：保持"已挖空"状态，
            // 下一 tick 第 1 步检测 isAir(target) 自动切回真正目标继续挖（一层层挖到矿）
            if (!this.goal.equals(this.target)) {
                return false;
            }
            this.clear();
            return ok;
        }
        int stage = this.digTicks * 10 / this.digTotal;
        MaidActions.showMiningProgress(maid, this.target, Math.min(9, stage));
        return false;
    }

    /** 水平是否够得着（3 格） */
    private boolean isHorizReach(SmartMaidEntity maid, BlockPos pos) {
        double dx = maid.getX() - (pos.getX() + 0.5D);
        double dz = maid.getZ() - (pos.getZ() + 0.5D);
        return dx * dx + dz * dz <= 9.0D;
    }

    /** 是否需要 pillar 搭高：目标在正上方且垂直超出可挖范围（目标在下方不算） */
    private boolean needPillar(SmartMaidEntity maid, BlockPos pos) {
        if (pos.getY() <= maid.blockPosition().getY()) {
            return false; // 不在上方
        }
        double dy = (pos.getY() + 0.5D) - (maid.getY() + 0.9D);
        return dy > VERT_HI;
    }

    /**
     * Pillar 原地搭高：起跳后往脚下放方块，落地站上新方块高度 +1。循环（由 tick 逐层驱动）
     * 直到目标够得着。不预先水平对准——垫高后由主循环重新评估（够得着就挖，不够就继续垫/导航）。
     *
     * <p><b>放块时机</b>：等脚底离开原格（{@code y - startY > 1.0}，原格完全空出、无实体碰撞）
     * 再放方块——否则 {@code BlockPlaceContext} 的实体碰撞检查会因女仆仍在原格而放置失败，
     * 导致没垫上、落地不升高、超时掉落。落地判定同样用高度（升高 ≥0.5 即完成本层）。</p>
     *
     * @return true = pillar 进行中/本层完成；false = 失败（无方块/超时）
     */
    private boolean pillar(SmartMaidEntity maid) {
        // 0) 确保主手有可垫方块（先普通建材，没有就用任意方块）
        if (!MaidActions.isBridgeBlock(maid.getMainHandItem())) {
            if (!MaidActions.equipBridgeBlockFromBackpack(maid)
                    && !MaidActions.equipBlockFromBackpack(maid)) {
                MaidDebug.log("Breaker pillar 无方块");
                return false;
            }
        }
        // 1) 未起跳 → 起跳
        if (!this.pillarJumped) {
            maid.getNavigation().stop();
            maid.getMoveControl().setWait();
            this.pillarJumped = true;
            this.pillarCell = maid.blockPosition().immutable();
            this.pillarTicks = 0;
            this.pillarStartY = maid.getY();
            maid.getJumpControl().jump();
            MaidDebug.log("Breaker pillar 起跳 @" + this.pillarCell + " y=" + this.pillarStartY
                    + " 尝试=" + this.pillarAttempts);
            return true;
        }

        // 持续锁定水平位置（防起跳后漂移掉下 1 格宽的柱子）
        maid.getNavigation().stop();
        maid.getMoveControl().setWait();

        this.pillarTicks++;
        // 2) 脚底离开原格（>1.0，原格完全空出无实体碰撞）→ 放方块到脚下（同格多次尝试，放上为止）
        if (maid.getY() - this.pillarStartY > 1.0D && maid.level().getBlockState(this.pillarCell).isAir()) {
            boolean placed = MaidActions.placeBlock(maid, this.pillarCell.below(), Direction.UP);
            MaidDebug.log("Breaker pillar 垫 @" + this.pillarCell + " placed=" + placed + " y=" + maid.getY());
        }
        // 3) 落地：脚底比起跳前升高 ≥0.5 格 → 站上新方块，本层完成
        if (maid.onGround() && maid.getY() - this.pillarStartY >= 0.5D) {
            MaidDebug.log("Breaker pillar 上一层 -> y=" + maid.getY() + " (原 " + this.pillarStartY + ")");
            this.pillarJumped = false;
            this.pillarCell = null;
            this.pillarAttempts = 0;
            return true;
        }
        // 4) 落地但没升高（放块失败）→ 重试起跳；连续失败超限才放弃
        if (maid.onGround() && this.pillarTicks > 3) {
            this.pillarJumped = false;
            this.pillarAttempts++;
            if (this.pillarAttempts >= 8) {
                MaidDebug.log("Breaker pillar 连续失败放弃 @" + this.pillarCell
                        + " y=" + maid.getY() + " 原=" + this.pillarStartY);
                this.pillarCell = null;
                return false;
            }
            MaidDebug.log("Breaker pillar 重试塔层 @" + this.pillarCell + " 尝试=" + this.pillarAttempts);
            return true;
        }
        // 5) 空中硬超时（异常情况：一直不落地）
        if (this.pillarTicks > PILLAR_TIMEOUT) {
            MaidDebug.log("Breaker pillar 空中超时 @" + this.pillarCell
                    + " y=" + maid.getY() + " 原=" + this.pillarStartY + " onGround=" + maid.onGround());
            this.pillarJumped = false;
            this.pillarCell = null;
            return false;
        }
        return true;
    }

    /** 中断：清理裂纹并复位 */
    public void abort(SmartMaidEntity maid) {
        if (this.target != null && !maid.level().isClientSide()) {
            MaidActions.showMiningProgress(maid, this.target, -1);
        }
        this.clear();
    }

    private void clear() {
        this.goal = null;
        this.target = null;
        this.digTicks = 0;
        this.digTotal = 0;
        this.pillarJumped = false;
        this.pillarCell = null;
        this.pillarAttempts = 0;
    }
}
