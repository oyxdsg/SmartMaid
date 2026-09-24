package com.oyxdsg.smartmaid.entity.ai;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;

/**
 * 女仆搭方块执行器：把「确保主手持方块 → 走到目标格旁 → 自动选支撑面放置方块」抽成独立状态机，
 * 供搭路/垫脚/爬高（遇沟、水面、向上需要垫方块）以及后续寻路降级复用。
 *
 * <p>放置支撑面选择：优先点 {@code target} 下方格子的上面（UP，可替换水/岩浆垫桥）；
 * 下方悬空（悬崖/虚空）时退而点 target 水平相邻实心格的朝向 target 的面。</p>
 *
 * <p>用法：{@code begin(maid, pos)}（背包无方块返回 false）；之后每服务端 tick 调
 * {@code tick(maid)}；返回 true 表示 target 格已放上方块（target 清空）。</p>
 */
public class MaidBlockPlacer {

    private BlockPos target;

    /** 直线接近模式（寻路降级用）：不再用原版 A* 导航，直接朝目标走 */
    private boolean moveStraight;

    /** 搭路模式（寻路降级用）：只消耗普通建材，不浪费贵重方块 */
    private boolean bridgeMode;

    public void setMoveStraight(boolean moveStraight) {
        this.moveStraight = moveStraight;
    }

    public void setBridgeMode(boolean bridgeMode) {
        this.bridgeMode = bridgeMode;
    }

    public boolean isActive() {
        return this.target != null;
    }

    public BlockPos target() {
        return this.target;
    }

    /** 开始往 pos 格搭方块；返回 false = 背包没有可放置方块 */
    public boolean begin(SmartMaidEntity maid, BlockPos pos) {
        if (maid.level().isClientSide()) {
            return false;
        }
        if (this.bridgeMode) {
            if (!MaidActions.hasBridgeBlock(maid)) {
                MaidDebug.log("Placer 搭路失败：背包没有可搭路的普通方块");
                return false;
            }
        } else if (!MaidActions.hasBlockItem(maid)) {
            return false;
        }
        // 预检：目标格必须能找到可点击的支撑面，否则搭不了（避免无限失败刷屏卡死）
        if (findClickFace(maid, pos) == null) {
            MaidDebug.log("Placer 无支撑面，拒绝 " + pos);
            return false;
        }
        this.target = pos.immutable();
        MaidDebug.log("Placer begin " + this.target);
        return true;
    }

    /**
     * 每服务端 tick 驱动：确保主手持方块 → 走到目标旁 → 选支撑面放置。
     *
     * @return true = target 格已放上方块（target 已清空）
     */
    public boolean tick(SmartMaidEntity maid) {
        if (this.target == null) {
            return false;
        }
        if (maid.level().isClientSide()) {
            return false;
        }
        // 目标格已有方块 → 本轮完成
        if (!maid.level().getBlockState(this.target).isAir()) {
            this.clear();
            return true;
        }
        // 确保主手是合适的方块物品（搭路模式只换普通建材，贵重方块也换掉）
        boolean handOk = this.bridgeMode
                ? MaidActions.isBridgeBlock(maid.getMainHandItem())
                : maid.getMainHandItem().getItem() instanceof BlockItem;
        if (!handOk) {
            boolean equipped = this.bridgeMode
                    ? MaidActions.equipBridgeBlockFromBackpack(maid)
                    : MaidActions.equipBlockFromBackpack(maid);
            if (!equipped) {
                this.clear();
                return false;
            }
        }
        // 先走到目标格旁
        if (!MaidActions.isWithinReach(maid, this.target, 3.0D)) {
            if (this.moveStraight) {
                maid.getMoveControl().setWantedPosition(
                        this.target.getX() + 0.5D, this.target.getY(), this.target.getZ() + 0.5D, 1.0D);
            } else if (maid.getNavigation().isDone() || maid.tickCount % 20 == 0) {
                MaidActions.navigateTo(maid, this.target, 1.0D);
            }
            return false;
        }
        maid.getNavigation().stop();
        // 选支撑面并放置
        Direction face = findClickFace(maid, this.target);
        if (face == null) {
            // 找不到可点击支撑面（目标四周全悬空）→ 中止本次搭方块，避免无限重试
            MaidDebug.log("Placer 无支撑面，中止 " + this.target);
            this.clear();
            return false;
        }
        BlockPos clickPos = this.target.relative(face.getOpposite());
        if (MaidActions.placeBlock(maid, clickPos, face)) {
            this.clear();
            return true;
        }
        return false;
    }

    public void abort() {
        this.clear();
    }

    /**
     * 找 target 格放置时可点击的支撑面：
     * 优先下方（UP，搭桥替换水/岩浆）；下方悬空则找水平相邻实心格的朝向 target 的面。
     */
    private Direction findClickFace(SmartMaidEntity maid, BlockPos pos) {
        Level level = maid.level();
        BlockPos below = pos.below();
        if (below.getY() >= level.getMinY() && !level.getBlockState(below).isAir()) {
            return Direction.UP;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(dir);
            if (!level.getBlockState(neighbor).isAir()) {
                return dir.getOpposite();
            }
        }
        return null;
    }

    private void clear() {
        this.target = null;
    }
}
