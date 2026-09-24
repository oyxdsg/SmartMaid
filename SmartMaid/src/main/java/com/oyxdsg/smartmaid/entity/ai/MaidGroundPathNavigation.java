package com.oyxdsg.smartmaid.entity.ai;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 女仆专用地面导航：注入自定义节点评估器 {@link MaidWalkNodeEvaluator}（支持跳跃跨障碍/沟），
 * 并在原版寻路与物理直线路径 {@link MaidStraightNav} 之间**动态切换**：
 *
 * <ul>
 *     <li>每次寻路先跑原版 A*：没路 / 路径没真正到目标（被墙水阻断） / 原版路径明显绕远
 *         （节点数 &gt; 直线距离 × {@link #RATIO}）→ 切直线模式</li>
 *     <li>直线模式中每 {@link #STRAIGHT_EVAL_INTERVAL} tick 重新评估一次原版寻路，
 *         一旦原版代价回落到阈值内 → 切回正常寻路</li>
 * </ul>
 */
public class MaidGroundPathNavigation extends GroundPathNavigation {

    /** 原版路径节点数超过"直线距离 × 该倍数"视为绕远，切直线 */
    private static final double RATIO = 3.0D;
    /** 直线距离太短（格）不折腾，直接正常寻路 */
    private static final double MIN_STRAIGHT_NODES = 8.0D;
    /** 直线模式中每多少 tick 重新评估一次原版寻路（是否切回） */
    private static final int STRAIGHT_EVAL_INTERVAL = 40;
    /** 路径终点距目标水平距离超过该值视为"没到目标"（被障碍阻断） */
    private static final double TARGET_REACH_DIST_SQ = 4.0D; // 2 格

    private final MaidStraightNav straightNav = new MaidStraightNav();
    private int lastStraightEval = -10000;

    public MaidGroundPathNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    public MaidStraightNav getStraightNav() {
        return this.straightNav;
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new MaidWalkNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }

    @Override
    public Path createPath(BlockPos pos, int accuracy) {
        SmartMaidEntity maid = (SmartMaidEntity) this.mob;
        // 坐下 = 原地不动：不产生任何新路径（也不激活直线降级）
        if (maid.isOrderedToSit()) {
            return null;
        }
        // 战斗期禁用直线降级（只走原版寻路 + 跳跃执行器，不挖不搭）
        if (combatDisablesStraight(maid)) {
            return super.createPath(pos, accuracy);
        }
        // 刚放弃过（无方块/不可挖等）→ 冷却期只走原版，避免原地抖动
        if (this.straightNav.recentlyGaveUp(maid.tickCount)) {
            return super.createPath(pos, accuracy);
        }
        // 直线模式中：40 tick 才重跑一次 A* 评估是否切回，平时保持直线
        if (this.straightNav.isActive()
                && maid.tickCount - this.lastStraightEval < STRAIGHT_EVAL_INTERVAL) {
            return null;
        }
        Path path = super.createPath(pos, accuracy);
        if (useStraight(maid, path, pos)) {
            MaidDebug.log("StraightNav 降级 -> " + pos
                    + " (直线=" + Math.round(horizontalDist(maid.blockPosition(), pos))
                    + "格 原版=" + (path == null ? "无路" : path.getNodeCount() + "节点") + ")");
            this.straightNav.setTarget(pos);
            this.lastStraightEval = maid.tickCount;
            return null;
        }
        // 原版路径代价在阈值内 → 恢复正常寻路（动态切回）
        if (this.straightNav.isActive()) {
            MaidDebug.log("StraightNav 切回原版 -> " + pos
                    + " (原版=" + (path == null ? "无路" : path.getNodeCount() + "节点") + ")");
        }
        this.straightNav.clear(maid);
        return path;
    }

    @Override
    public Path createPath(Entity entity, int accuracy) {
        SmartMaidEntity maid = (SmartMaidEntity) this.mob;
        // 坐下 = 原地不动：不产生任何新路径（也不激活直线降级）
        if (maid.isOrderedToSit()) {
            return null;
        }
        if (combatDisablesStraight(maid)) {
            return super.createPath(entity, accuracy);
        }
        BlockPos target = entity.blockPosition();
        if (this.straightNav.recentlyGaveUp(maid.tickCount)) {
            return super.createPath(entity, accuracy);
        }
        if (this.straightNav.isActive()
                && maid.tickCount - this.lastStraightEval < STRAIGHT_EVAL_INTERVAL) {
            return null;
        }
        Path path = super.createPath(entity, accuracy);
        if (useStraight(maid, path, target)) {
            this.straightNav.setTarget(target);
            this.lastStraightEval = maid.tickCount;
            return null;
        }
        if (this.straightNav.isActive()) {
            MaidDebug.log("StraightNav 切回原版(追实体) -> " + target);
        }
        this.straightNav.clear(maid);
        return path;
    }

    @Override
    public void tick() {
        SmartMaidEntity maid = (SmartMaidEntity) this.mob;
        // 坐下 = 原地不动：清掉直线降级与原版路径，本 tick 不驱动任何移动
        if (maid.isOrderedToSit()) {
            if (this.straightNav.isActive()) {
                this.straightNav.clear(maid);
            }
            this.stop();
            return;
        }
        // 直线降级模式激活时，接管本 tick（移动+破坏+搭路由 straightNav 驱动）
        if (this.straightNav.isActive()) {
            if (this.straightNav.tick((SmartMaidEntity) this.mob)) {
                this.straightNav.clear((SmartMaidEntity) this.mob);
            }
            return;
        }
        super.tick();
    }

    /** 战斗期禁用直线降级：清掉可能残留的直线目标，一律交原版寻路 + 跳跃执行器。 */
    private boolean combatDisablesStraight(SmartMaidEntity maid) {
        if (maid.isCombatActive()) {
            if (this.straightNav.isActive()) {
                MaidDebug.log("Combat 禁用直线降级（战斗期只走原版寻路，不挖不搭）");
            }
            this.straightNav.clear(maid);
            return true;
        }
        return false;
    }

    /** 是否应该走直线模式：没路 / 部分路径（到不了目标） / 原版明显绕远 */
    private boolean useStraight(SmartMaidEntity maid, Path path, BlockPos target) {        if (path == null) {
            return true;
        }
        if (!reachesTarget(maid, path, target)) {
            return true;
        }
        double straight = horizontalDist(maid.blockPosition(), target);
        double threshold = Math.max(MIN_STRAIGHT_NODES, straight * RATIO);
        return path.getNodeCount() > threshold;
    }

    private double horizontalDist(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 路径终点是否真正接近目标。高度差大的（坡/爬升）视为原版路径合理可达；
     *  只有同高且近但被方块隔开（墙封死）时才视为"没到目标"。 */
    private boolean reachesTarget(SmartMaidEntity maid, Path path, BlockPos target) {
        Node end = path.getEndNode();
        if (end == null) {
            return false;
        }
        BlockPos e = end.asBlockPos();
        double dx = e.getX() - target.getX();
        double dz = e.getZ() - target.getZ();
        if (dx * dx + dz * dz > TARGET_REACH_DIST_SQ) {
            return false;
        }
        // 高度差大（坡/爬升）：原版路径合理，女仆自己能走上去，不降级
        if (Math.abs(e.getY() - target.getY()) > 1.5D) {
            return true;
        }
        // 同高且近：做视线检测，被方块挡住（隔墙）则视为没到目标
        HitResult hit = maid.level().clip(new ClipContext(
                Vec3.atCenterOf(e), Vec3.atCenterOf(target),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, maid));
        return hit.getType() != HitResult.Type.BLOCK;
    }
}
