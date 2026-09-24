package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.craft.CraftExecutor;
import net.minecraft.world.item.ItemStack;

/**
 * 制作任务（M3）：消耗背包材料直接合成目标物品，count 达成结束。
 *
 * <p>AI 只需报成品目标，女仆自动找配方、凑材料、原地合成（无需工作台）。</p>
 */
public class CraftTask extends MaidAITask {

    private final ItemStack target;
    private final int count;
    private int made;
    private boolean outOfMaterial;

    public CraftTask(ItemStack target, int count) {
        super("craft");
        this.target = target.copy();
        this.count = Math.max(1, count);
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return this.target != null && !this.target.isEmpty();
    }

    @Override
    public void start(SmartMaidEntity maid) {
        this.made = 0;
        this.outOfMaterial = false;
        MaidDebug.log("Craft start 目标=" + this.target.getItem().getDescriptionId() + " x" + this.count);
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        if (this.made >= this.count) {
            return;
        }
        ItemStack out = CraftExecutor.craft(maid, this.target);
        if (out.isEmpty()) {
            this.outOfMaterial = true; // 缺材料或无配方
            maid.showBubble(net.minecraft.network.chat.Component.literal("材料不够做 " + this.target.getItem().getDescriptionId()), 60);
            return;
        }
        this.made += out.getCount();
    }

    @Override
    public boolean isDone() {
        return this.made >= this.count || this.outOfMaterial;
    }

    @Override
    public String result() {
        return this.outOfMaterial
                ? "缺材料，已制作 " + this.made + " 个"
                : "已制作 " + this.made + " 个 " + this.target.getItem().getDescriptionId();
    }
}
