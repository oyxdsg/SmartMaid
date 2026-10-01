package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.slot.Slots;
import net.minecraft.core.BlockPos;

/**
 * 存入箱子（一个原子队列项，DESIGN_MAID_MENU_N1 §3.5）：内部两步「开箱 → 放入主手物品」。
 *
 * <p>打包成一项的原因：拆成 {@code chestopen}+{@code chestput} 两项时，前一步失败后一步必然失败，
 * 需要引入"组失败"复杂度；打包后"一个项 = 一个原子语义单位"，失败策略干净。</p>
 */
public class ChestStoreTask extends MaidAITask {

    private final BlockPos hint;
    private final int count;
    private final ChestOpenTask open;
    private TransferTask put;
    private boolean opened;
    private boolean failed;

    public ChestStoreTask(BlockPos hint, int count) {
        super("cheststore");
        this.hint = hint;
        this.count = Math.max(1, count);
        this.open = new ChestOpenTask(hint);
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return this.hint != null;
    }

    @Override
    public void start(SmartMaidEntity maid) {
        this.opened = false;
        this.failed = false;
        this.put = null;
        this.open.start(maid);
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        if (this.failed) {
            return;
        }
        if (!this.opened) {
            this.open.tick(maid);
            if (this.open.isDone()) {
                if (maid.getOpenedContainer() == null) {
                    this.failed = true;
                    MaidDebug.log("ChestStore 失败：未能打开箱子");
                    return;
                }
                this.opened = true;
            }
            return;
        }
        if (this.put == null) {
            BlockPos cp = maid.getOpenedContainer();
            if (cp == null) {
                this.failed = true;
                return;
            }
            this.put = new TransferTask(Slots.mainhand(), Slots.container(cp, -1), this.count);
            this.put.start(maid);
        }
        this.put.tick(maid);
    }

    @Override
    public boolean isDone() {
        return this.failed || (this.put != null && this.put.isDone());
    }

    @Override
    public void forceStop(SmartMaidEntity maid) {
        if (this.put != null) {
            this.put.forceStop(maid);
        }
    }

    @Override
    public String result() {
        return this.failed ? "存箱失败（箱子不可达）" : "已存入箱子";
    }
}
