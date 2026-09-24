package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/**
 * 进食任务：吃背包里的食物（{@code itemFilter} 为空则吃营养最高、其次饱和度最高的食物）。
 *
 * <p>换到主手后走原版 {@code startUsingItem(MAINHAND)}，由 {@code LivingEntity.tick}
 * 自动推进并在完成时调用 {@code completeUsingItem}：食物效果由 {@code Consumable} 应用，
 * 饱食度由 {@link SmartMaidEntity#completeUsingItem()} 补记（女仆非玩家，原版只给玩家加饱食）。</p>
 */
public class EatTask extends MaidAITask {

    /** 兜底超时（防止使用状态异常卡住任务） */
    private static final int MAX_TICKS = 20 * 10;

    private final Item itemFilter;
    private boolean failed;
    private boolean finished;
    private int ticks;

    public EatTask(Item itemFilter) {
        super("eat");
        this.itemFilter = itemFilter;
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return true;
    }

    @Override
    public void start(SmartMaidEntity maid) {
        int slot = MaidActions.findFoodSlot(maid, this.itemFilter);
        if (slot < 0) {
            this.failed = true;
            maid.showBubble(Component.literal(this.itemFilter == null ? "没有可吃的食物" : "背包里没有这个食物"), 60);
            return;
        }
        if (!MaidActions.swapToMainhand(maid, slot) || !MaidActions.startEating(maid)) {
            this.failed = true;
            maid.showBubble(Component.literal("这个吃不了"), 60);
            return;
        }
        MaidDebug.log("Eat start slot=" + slot + " item=" + MaidDebug.itemName(maid.getMainHandItem()));
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        this.ticks++;
        if (!this.failed && !maid.isUsingItem()) {
            this.finished = true;
        }
    }

    @Override
    public boolean isDone() {
        return this.failed || this.finished || this.ticks > MAX_TICKS;
    }

    @Override
    public String result() {
        return this.failed ? "没有食物" : "已进食";
    }

    @Override
    public void forceStop(SmartMaidEntity maid) {
        if (maid.isUsingItem()) {
            maid.stopUsingItem();
        }
    }
}
