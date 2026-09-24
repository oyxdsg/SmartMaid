package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionBlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * 打开容器任务（存箱子第一步）：在提示位置附近定位实际容器 → 走到面前 →
 * 触发真实开箱动画（ChestBlockEntity.startOpen，女仆实现 ContainerUser）→ 女仆动作 →
 * 记录"当前打开的箱子"，供后续 chestput / chesttake 使用。
 */
public class ChestOpenTask extends MaidAITask {

    /** 在提示位置周围搜索容器的半径 */
    private static final int SEARCH_RADIUS = 4;

    private final BlockPos hint;
    private BlockPos target;
    private boolean done;

    public ChestOpenTask(BlockPos hint) {
        super("chestopen");
        this.hint = hint;
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return this.hint != null;
    }

    @Override
    public void start(SmartMaidEntity maid) {
        this.done = false;
        // 智能定位：先看提示位置本身，再在周围搜索最近的容器
        this.target = findContainerNear(maid, this.hint);
        if (this.target == null) {
            maid.showBubble(net.minecraft.network.chat.Component.literal("附近没有箱子"), 60);
            MaidDebug.log("ChestOpen 失败: " + this.hint + " 附近无容器");
            this.done = true;
            return;
        }
        MaidDebug.log("ChestOpen start hint=" + this.hint + " -> 实际容器=" + this.target);
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        if (this.done || this.target == null) {
            return;
        }
        if (!MaidActions.isWithinReach(maid, this.target, 2.5D)) {
            if (maid.getNavigation().isDone() || maid.tickCount % 20 == 0) {
                MaidActions.navigateTo(maid, this.target, 1.0D);
            }
            return;
        }
        maid.getNavigation().stop();
        // 面朝箱子 + 女仆开箱动作
        maid.getLookControl().setLookAt(this.target.getX() + 0.5D, this.target.getY() + 0.5D, this.target.getZ() + 0.5D);
        maid.swing(InteractionHand.MAIN_HAND);
        // 触发真实开箱动画（openCount 机制，客户端箱子盖打开）
        BlockEntity be = maid.level().getBlockEntity(this.target);
        if (be instanceof ChestBlockEntity chest) {
            chest.startOpen(maid);
            MaidDebug.log("ChestOpen 开箱动画: " + this.target);
        } else {
            // 非箱子容器（如熔炉）无开盖动画，仅记录
            MaidDebug.log("ChestOpen 容器(非箱子): " + this.target);
        }
        // 记录为"当前打开的箱子"
        maid.setOpenedContainer(this.target);
        this.done = true;
    }

    @Override
    public boolean isDone() {
        return this.done;
    }

    @Override
    public String result() {
        return this.done ? "已打开箱子 " + this.target : "打开中";
    }

    /** 在提示位置及其周围搜索最近的容器方块（箱子/熔炉/漏斗等 Container）。实现已收拢到感知工具。 */
    private BlockPos findContainerNear(SmartMaidEntity maid, BlockPos hint) {
        if (hint != null && PerceptionBlockUtil.isContainerAt(maid.level(), hint)) {
            return hint;
        }
        BlockPos center = hint != null ? hint : maid.blockPosition();
        return PerceptionBlockUtil.findNearestContainer(maid.level(), center, SEARCH_RADIUS);
    }
}
