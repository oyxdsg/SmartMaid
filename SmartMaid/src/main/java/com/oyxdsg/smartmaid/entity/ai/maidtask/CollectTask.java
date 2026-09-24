package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * 收集任务（M3）：范围内拾取掉落物（女仆 setCanPickUpLoot 自动拾取进背包 0-35），
 * 范围内无目标掉落物结束。
 */
public class CollectTask extends MaidAITask {

    private final double range;
    private final Predicate<ItemStack> filter;
    private boolean done;

    public CollectTask(double range, Predicate<ItemStack> filter) {
        super("collect");
        this.range = range;
        this.filter = filter;
    }

    public CollectTask(double range) {
        this(range, null);
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return true;
    }

    @Override
    public void start(SmartMaidEntity maid) {
        this.done = false;
        MaidDebug.log("Collect start range=" + range);
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        ItemEntity item = MaidActions.findNearestItem(maid, this.range, this.filter);
        if (item == null) {
            this.done = true; // 范围内无目标掉落物 → 完成
            return;
        }
        // 靠近时解除拾取延迟，女仆原版拾取逻辑收进背包（0-35）
        if (maid.distanceToSqr(item) < 2.0D * 2.0D) {
            item.setNoPickUpDelay();
        }
        if (maid.getNavigation().isDone() || maid.tickCount % 20 == 0) {
            MaidActions.navigateTo(maid, item.blockPosition(), 1.0D);
        }
    }

    @Override
    public boolean isDone() {
        return this.done;
    }

    @Override
    public String result() {
        return "收集完成";
    }
}
