package com.oyxdsg.smartmaid.entity.ai.perception.sense;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.perception.MaidPerception;
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionBlockUtil;
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionUtil;
import com.oyxdsg.smartmaid.entity.ai.perception.Sense;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 方块感知通道：脚下方块 / 视线射线命中 / 附近容器 / 矿石 / 成熟作物 / 危险方块。
 *
 * <p>interval 10（0.5s），其中大范围扫描（容器/矿石/作物/危险）内部再按
 * {@value #SCAN_EVERY} tick 门控，避免每 0.5s 全量扫描。区块 {@code blocks}：</p>
 * <ul>
 *     <li>feet / below：脚下方块与下 1 格（id + 分类）</li>
 *     <li>facing：视线射线（32 格）命中方块（"我看着什么"）</li>
 *     <li>containers[]：8 格内容器（箱子/熔炉/漏斗）</li>
 *     <li>ores[]：6 格内矿石</li>
 *     <li>crops[]：6 格内成熟作物</li>
 *     <li>danger[]：4 格内危险方块（岩浆/火/岩浆块/水/仙人掌）</li>
 * </ul>
 */
public class BlockSense implements Sense {

    /** 大范围扫描门控（tick），1s 一次 */
    private static final int SCAN_EVERY = 20;
    private static final int CONTAINER_RANGE = 8;
    private static final int ORE_RANGE = 6;
    private static final int CROP_RANGE = 6;
    private static final int DANGER_RANGE = 4;
    private static final int MAX_ENTRIES = 6;

    @Override
    public int intervalTicks() {
        return 10;
    }

    @Override
    public String sectionName() {
        return "blocks";
    }

    @Override
    public void collect(SmartMaidEntity maid, MaidPerception data) {
        Level level = maid.level();
        JsonObject blocks = data.blocks();
        BlockPos feet = maid.blockPosition();

        // 脚下方块 / 下 1 格
        writeBlockEntry(blocks, "feet", level, feet);
        writeBlockEntry(blocks, "below", level, feet.below());

        // 视线射线：眼睛朝 look 方向 32 格
        Vec3 eye = maid.getEyePosition();
        Vec3 to = eye.add(maid.getLookAngle().scale(32.0D));
        BlockHitResult hit = level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, maid));
        if (hit.getType() != HitResult.Type.MISS) {
            JsonObject facing = new JsonObject();
            PerceptionUtil.blockPos(facing, hit.getBlockPos());
            BlockState state = level.getBlockState(hit.getBlockPos());
            facing.addProperty("id", PerceptionUtil.blockId(state));
            facing.addProperty("type", PerceptionUtil.classifyBlock(level, hit.getBlockPos()));
            blocks.add("facing", facing);
        }

        // 大范围扫描（内部门控）
        if (maid.tickCount % SCAN_EVERY != 0) {
            return;
        }
        blocks.add("containers", collectContainers(level, feet));
        blocks.add("ores", collectOres(level, feet));
        blocks.add("crops", collectCrops(level, feet));
        blocks.add("danger", collectDanger(level, feet));
    }

    private static void writeBlockEntry(JsonObject blocks, String key, Level level, BlockPos pos) {
        JsonObject o = new JsonObject();
        BlockState state = level.getBlockState(pos);
        o.addProperty("id", PerceptionUtil.blockId(state));
        o.addProperty("type", PerceptionUtil.classifyBlock(level, pos));
        blocks.add(key, o);
    }

    private static JsonArray collectContainers(Level level, BlockPos center) {
        JsonArray arr = new JsonArray();
        List<BlockPos> found = new ArrayList<>();
        BlockPos a = center.offset(-CONTAINER_RANGE, -2, -CONTAINER_RANGE);
        BlockPos b = center.offset(CONTAINER_RANGE, 2, CONTAINER_RANGE);
        for (BlockPos p : BlockPos.betweenClosed(a, b)) {
            if (found.size() >= MAX_ENTRIES) {
                break;
            }
            if (PerceptionBlockUtil.isContainerAt(level, p)) {
                found.add(p.immutable());
            }
        }
        found.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos p : found) {
            arr.add(blockEntry(level, p, center));
        }
        return arr;
    }

    private static JsonArray collectOres(Level level, BlockPos center) {
        JsonArray arr = new JsonArray();
        BlockPos a = center.offset(-ORE_RANGE, -ORE_RANGE, -ORE_RANGE);
        BlockPos b = center.offset(ORE_RANGE, ORE_RANGE, ORE_RANGE);
        for (BlockPos p : BlockPos.betweenClosed(a, b)) {
            if (arr.size() >= MAX_ENTRIES) {
                break;
            }
            if (PerceptionBlockUtil.isOre(level.getBlockState(p))) {
                arr.add(blockEntry(level, p, center));
            }
        }
        return arr;
    }

    private static JsonArray collectCrops(Level level, BlockPos center) {
        JsonArray arr = new JsonArray();
        BlockPos a = center.offset(-CROP_RANGE, -1, -CROP_RANGE);
        BlockPos b = center.offset(CROP_RANGE, 1, CROP_RANGE);
        for (BlockPos p : BlockPos.betweenClosed(a, b)) {
            if (arr.size() >= MAX_ENTRIES) {
                break;
            }
            if (PerceptionBlockUtil.isMatureCrop(level.getBlockState(p))) {
                arr.add(blockEntry(level, p, center));
            }
        }
        return arr;
    }

    private static JsonArray collectDanger(Level level, BlockPos center) {
        JsonArray arr = new JsonArray();
        BlockPos a = center.offset(-DANGER_RANGE, -2, -DANGER_RANGE);
        BlockPos b = center.offset(DANGER_RANGE, 2, DANGER_RANGE);
        for (BlockPos p : BlockPos.betweenClosed(a, b)) {
            if (arr.size() >= MAX_ENTRIES) {
                break;
            }
            if (PerceptionBlockUtil.isDangerBlock(level, p)) {
                arr.add(blockEntry(level, p, center));
            }
        }
        return arr;
    }

    private static JsonObject blockEntry(Level level, BlockPos p, BlockPos center) {
        JsonObject o = new JsonObject();
        PerceptionUtil.blockPos(o, p);
        BlockState state = level.getBlockState(p);
        o.addProperty("id", PerceptionUtil.blockId(state));
        o.addProperty("dist", Math.round(Math.sqrt(center.distSqr(p)) * 10.0D) / 10.0D);
        return o;
    }
}
