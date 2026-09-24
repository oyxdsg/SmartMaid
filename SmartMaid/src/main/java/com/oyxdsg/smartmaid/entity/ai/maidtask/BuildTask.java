package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidBlockPlacer;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 建造任务（M3）：按目标坐标列表逐格放置方块，列表放完或缺方块结束。
 *
 * <p>放置复用 {@link MaidBlockPlacer}（自动换方块到主手、走到目标旁、选支撑面放置）。
 * 蓝图（相对坐标模板）为后续扩展。</p>
 */
public class BuildTask extends MaidAITask {

    private final List<BlockPos> positions;
    private int index;
    private boolean outOfBlock;

    // 搭方块执行器（复用 MaidBlockPlacer：自动选支撑面放置）
    private final MaidBlockPlacer placer = new MaidBlockPlacer();

    public BuildTask(List<BlockPos> positions) {
        super("build");
        this.positions = positions;
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return this.positions != null && !this.positions.isEmpty();
    }

    @Override
    public void start(SmartMaidEntity maid) {
        this.index = 0;
        this.outOfBlock = false;
        // 换方块到主手交给 MaidBlockPlacer 在 tick 内自动处理
        MaidDebug.log("Build start 位置数=" + positions.size());
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        if (this.index >= this.positions.size()) {
            return;
        }
        BlockPos pos = this.positions.get(this.index);
        // 目标格已有方块 → 跳过
        BlockState state = maid.level().getBlockState(pos);
        if (!state.isAir()) {
            this.index++;
            return;
        }
        // 开始往 pos 搭方块
        if (!this.placer.isActive()) {
            if (!this.placer.begin(maid, pos)) {
                this.outOfBlock = true;
                maid.showBubble(Component.literal("没有方块可以放啦"), 60);
                return;
            }
        }
        // 驱动搭方块，放上后进入下一个位置
        if (this.placer.tick(maid)) {
            this.index++;
        }
    }

    @Override
    public boolean isDone() {
        return this.index >= this.positions.size() || this.outOfBlock;
    }

    @Override
    public String result() {
        return this.outOfBlock
                ? "缺方块，已放置 " + this.index + "/" + this.positions.size()
                : "已放置 " + this.index + "/" + this.positions.size();
    }

    @Override
    public void forceStop(SmartMaidEntity maid) {
        this.placer.abort();
    }
}
