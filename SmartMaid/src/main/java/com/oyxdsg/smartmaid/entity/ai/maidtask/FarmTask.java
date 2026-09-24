package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionBlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 耕作任务（M3）：区域内收成熟作物 + 空地播种，区域处理完结束。
 *
 * <p>播种用通用 {@link MaidActions#useItemOn}（种子右键耕地，26.2 已移除 ItemNameBlockItem，不依赖具体类）。</p>
 */
public class FarmTask extends MaidAITask {

    private final BlockPos center;
    private final int range;
    private boolean done;

    public FarmTask(BlockPos center, int range) {
        super("farm");
        this.center = center;
        this.range = range;
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return this.center != null;
    }

    @Override
    public void start(SmartMaidEntity maid) {
        this.done = false;
        MaidDebug.log("Farm start center=" + center + " range=" + range);
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        // 1. 优先收成熟作物
        BlockPos crop = findNearestMatureCrop(maid);
        if (crop != null) {
            handlePosition(maid, crop);
            return;
        }
        // 2. 无成熟作物 → 找空地播种
        BlockPos plantPos = findPlantableSpot(maid);
        if (plantPos != null) {
            handlePlant(maid, plantPos);
            return;
        }
        // 3. 无成熟作物且无空地 → 完成
        this.done = true;
    }

    @Override
    public boolean isDone() {
        return this.done;
    }

    @Override
    public String result() {
        return "耕作区域处理完毕";
    }

    private void handlePosition(SmartMaidEntity maid, BlockPos pos) {
        if (MaidActions.isWithinReach(maid, pos, 3.0D)) {
            MaidActions.breakBlock(maid, pos); // 收获：破坏作物，掉落进背包
        } else if (maid.getNavigation().isDone() || maid.tickCount % 20 == 0) {
            MaidActions.navigateTo(maid, pos, 1.0D);
        }
    }

    private void handlePlant(SmartMaidEntity maid, BlockPos pos) {
        // 主手不是可播种方块（种子是 BlockItem）→ 从背包换
        if (!(maid.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem)) {
            boolean equipped = MaidActions.equipFromBackpack(maid,
                    stack -> stack.getItem() instanceof net.minecraft.world.item.BlockItem);
            if (!equipped) {
                // 没有种子可播 → 视为处理完（无法继续）
                this.done = true;
                return;
            }
        }
        if (MaidActions.isWithinReach(maid, pos, 3.0D)) {
            MaidActions.useItemOn(maid, pos.below(), Direction.UP); // 右键耕地播种
        } else if (maid.getNavigation().isDone() || maid.tickCount % 20 == 0) {
            MaidActions.navigateTo(maid, pos, 1.0D);
        }
    }

    /** 区域内找最近成熟作物 */
    private BlockPos findNearestMatureCrop(SmartMaidEntity maid) {
        return scanArea(maid).stream()
                .filter(p -> isMatureCrop(maid.level().getBlockState(p)))
                .min(Comparator.comparingDouble(p -> p.distSqr(maid.blockPosition())))
                .orElse(null);
    }

    /** 区域内找可播种空地：脚下是耕地、上方是空气 */
    private BlockPos findPlantableSpot(SmartMaidEntity maid) {
        for (BlockPos pos : scanArea(maid)) {
            BlockState ground = maid.level().getBlockState(pos);
            if (!ground.isAir()) {
                continue;
            }
            BlockState farmland = maid.level().getBlockState(pos.below());
            if (farmland.is(Blocks.FARMLAND)) {
                return pos;
            }
        }
        return null;
    }

    /** 是否为成熟作物。实现已收拢到感知工具 {@link PerceptionBlockUtil}。 */
    public static boolean isMatureCrop(BlockState state) {
        return PerceptionBlockUtil.isMatureCrop(state);
    }

    private List<BlockPos> scanArea(SmartMaidEntity maid) {
        Level level = maid.level();
        List<BlockPos> list = new ArrayList<>();
        BlockPos a = this.center.offset(-this.range, -1, -this.range);
        BlockPos b = this.center.offset(this.range, 1, this.range);
        for (BlockPos pos : BlockPos.betweenClosed(a, b)) {
            list.add(pos.immutable());
        }
        return list;
    }
}
