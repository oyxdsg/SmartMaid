package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 喂食任务（M3）：走到玩家面前 → 面朝玩家 → 找背包食物 → 把食物"丢出来"给玩家。
 * 食物已丢出即完成。
 */
public class FeedTask extends MaidAITask {

    private static final double FACE_DISTANCE = 2.0D;
    private boolean done;

    public FeedTask() {
        super("feed");
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return maid.getOwner() instanceof Player;
    }

    @Override
    public void start(SmartMaidEntity maid) {
        this.done = false;
        MaidDebug.log("Feed start");
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        Player owner = (Player) maid.getOwner();
        if (owner == null || !owner.isAlive()) {
            this.done = true;
            return;
        }
        // 1. 走到玩家面前
        double distSqr = maid.distanceToSqr(owner);
        if (distSqr > FACE_DISTANCE * FACE_DISTANCE) {
            if (maid.getNavigation().isDone() || maid.tickCount % 20 == 0) {
                maid.getNavigation().moveTo(owner, 1.0D);
            }
            return;
        }
        maid.getNavigation().stop();
        // 2. 面朝玩家
        maid.getLookControl().setLookAt(owner, 10.0F, (float) maid.getMaxHeadXRot());
        // 3. 找背包食物 → 换到主手
        ItemStack food = findFood(maid);
        if (food == null) {
            maid.showBubble(net.minecraft.network.chat.Component.literal("背包里没有食物了"), 60);
            this.done = true;
            return;
        }
        // 4. 丢出来给玩家
        if (MaidActions.isFood(maid.getMainHandItem())) {
            ItemStack drop = maid.getMainHandItem().split(1);
            MaidActions.throwItem(maid, drop, owner, 0.5D);
            MaidDebug.log("Feed 丢出食物: " + drop.getItem().getDescriptionId());
            this.done = true;
        }
    }

    @Override
    public boolean isDone() {
        return this.done;
    }

    @Override
    public String result() {
        return this.done ? "已喂食" : "喂食中断";
    }

    private ItemStack findFood(SmartMaidEntity maid) {
        if (MaidActions.isFood(maid.getMainHandItem())) {
            return maid.getMainHandItem();
        }
        int slot = MaidActions.findInBackpack(maid, MaidActions::isFood);
        if (slot < 0) {
            return null;
        }
        // 换到主手（热键0）
        if (!MaidActions.equipFromBackpack(maid, MaidActions::isFood)) {
            return null;
        }
        return maid.getMainHandItem();
    }
}
