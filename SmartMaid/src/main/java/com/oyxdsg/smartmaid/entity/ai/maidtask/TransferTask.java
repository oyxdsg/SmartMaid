package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.slot.ItemSlot;
import com.oyxdsg.smartmaid.entity.ai.slot.Slots;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * 物品转移任务（正交基础指令 transfer）：把物品从槽位 A 移动到槽位 B。
 *
 * <p>覆盖全部物品操作场景：</p>
 * <ul>
 *     <li>换主手：{@code transfer inv:N mainhand}（= 旧 equip）</li>
 *     <li>收纳：{@code transfer mainhand inv:5}（= 旧 store）</li>
 *     <li>丢出：{@code transfer mainhand world:x,y,z}（= 旧 drop）</li>
 *     <li>放置方块：{@code transfer mainhand world:x,y,z}（BlockItem 自动放置）</li>
 *     <li>穿装备：{@code transfer inv:N chest|head|legs|feet}</li>
 *     <li>放箱子：{@code transfer inv:N container:x,y,z}（auto 找空槽）</li>
 *     <li>取箱子：{@code transfer container:x,y,z inv:N}</li>
 * </ul>
 *
 * <p><b>地面（world）只能作转移目标</b>（放置/丢出）；地面→背包的拾取由 collect/pickup
 * 指令走过去完成，避免凭空吸物。</p>
 */
public class TransferTask extends MaidAITask {

    private final ItemSlot from;
    private final ItemSlot to;
    private final int count;
    private boolean done;
    private String outcome = "未执行";

    public TransferTask(ItemSlot from, ItemSlot to, int count) {
        super("transfer");
        this.from = from;
        this.to = to;
        this.count = Math.max(1, count);
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return this.from != null && this.to != null;
    }

    @Override
    public void start(SmartMaidEntity maid) {
        MaidDebug.log("Transfer start from=" + this.from + " to=" + this.to + " count=" + this.count);
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        if (this.done) {
            return;
        }
        // 靠近 from/to 所需的位置（world/container 槽需走过去操作）
        BlockPos need = nearestReachPos(maid);
        if (need != null && !MaidActions.isWithinReach(maid, need, 2.5D)) {
            if (maid.getNavigation().isDone() || maid.tickCount % 20 == 0) {
                MaidActions.navigateTo(maid, need, 1.0D);
            }
            return;
        }
        maid.getNavigation().stop();
        doTransfer(maid);
        this.done = true;
    }

    @Override
    public boolean isDone() {
        return this.done;
    }

    @Override
    public String result() {
        return this.outcome;
    }

    private void doTransfer(SmartMaidEntity maid) {
        if (Slots.isWorldSlot(this.from)) {
            this.outcome = "地面不能作为转移源，拾取请用 collect/pickup 指令";
            MaidDebug.log("Transfer 拒绝: 地面不能作源");
            return;
        }
        ItemStack extracted = this.from.extract(maid, this.count);
        if (extracted.isEmpty()) {
            this.outcome = "源槽位为空";
            MaidDebug.log("Transfer 失败: " + this.from + " 为空");
            maid.showBubble(net.minecraft.network.chat.Component.literal(this.from + " 是空的"), 60);
            return;
        }
        // 目标不接受此物品（如非装备塞盔甲槽）→ 丢到主人身边
        if (!this.to.canAccept(maid, extracted)) {
            MaidActions.dropToOwner(maid, extracted);
            this.outcome = "目标不接受 " + extracted.getItem().getDescriptionId() + "，已丢给主人";
            MaidDebug.log("Transfer 掉落: " + this.from + " -> " + this.to + " " + this.outcome);
            return;
        }
        ItemStack left = this.to.insert(maid, extracted);
        if (left.isEmpty()) {
            this.outcome = "已转移 " + extracted.getCount() + " 个 " + extracted.getItem().getDescriptionId();
            MaidDebug.log("Transfer 完成: " + this.from + " -> " + this.to + " " + this.outcome);
            return;
        }
        if (left.getCount() == extracted.getCount()) {
            if (this.to.isSwapAllowed()) {
                // 女仆自身槽位被不同物品占用 → 交换两个槽位
                ItemStack displaced = this.to.extract(maid, Integer.MAX_VALUE);
                ItemStack leftAfter = this.to.insert(maid, left);
                if (!leftAfter.isEmpty()) {
                    Block.popResource(maid.level(), maid.blockPosition(), leftAfter);
                }
                if (!displaced.isEmpty()) {
                    ItemStack back = this.from.insert(maid, displaced);
                    if (!back.isEmpty()) {
                        Block.popResource(maid.level(), maid.blockPosition(), back);
                    }
                }
                this.outcome = "已交换：目标槽原物品移到 " + this.from;
                MaidDebug.log("Transfer 交换: " + this.from + " <-> " + this.to + " " + this.outcome);
                return;
            }
            // 容器放不下（满/无容器）：物品放回源槽，提示，不交换不掉落
            ItemStack back = this.from.insert(maid, left);
            if (!back.isEmpty()) {
                Block.popResource(maid.level(), maid.blockPosition(), back);
            }
            this.outcome = "目标放不下（" + this.to + "），已放回" + this.from;
            MaidDebug.log("Transfer 容器满: " + this.from + " -> " + this.to + " " + this.outcome);
            maid.showBubble(net.minecraft.network.chat.Component.literal("箱子放不下，物品已放回"), 60);
            return;
        }
        // 部分堆叠（目标同类有剩余空间）→ 剩余放回源
        ItemStack back = this.from.insert(maid, left);
        if (!back.isEmpty()) {
            Block.popResource(maid.level(), maid.blockPosition(), back);
        }
        this.outcome = "已转移 " + (extracted.getCount() - left.getCount()) + " 个，剩余 " + left.getCount() + " 个放回";
        MaidDebug.log("Transfer 部分堆叠: " + this.from + " -> " + this.to + " " + this.outcome);
    }

    /** from/to 需要靠近的位置中更远的那个（都要靠近时） */
    private BlockPos nearestReachPos(SmartMaidEntity maid) {
        BlockPos a = this.from.reachPos();
        BlockPos b = this.to.reachPos();
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return maid.distanceToSqr(a.getX(), a.getY(), a.getZ())
                > maid.distanceToSqr(b.getX(), b.getY(), b.getZ()) ? a : b;
    }
}
