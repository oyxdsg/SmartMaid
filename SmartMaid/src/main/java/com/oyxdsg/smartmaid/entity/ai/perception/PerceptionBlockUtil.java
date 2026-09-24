package com.oyxdsg.smartmaid.entity.ai.perception;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.combat.MaidTargetFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;

/**
 * 感知方块/实体判定工具：从各任务类中"抽象收拢"的判定逻辑统一入口。
 *
 * <p>来源：{@code MineTask.isOre} / {@code FarmTask.isMatureCrop} / {@code ChestOpenTask.findContainerNear} /
 * {@code SmeltTask.findFurnace} / {@code AttackTask.findNearestHostile}。各任务改调此处（行为不变），
 * 保证"AI 看到的"与"女仆实际能做的"用同一套判定。</p>
 */
public final class PerceptionBlockUtil {

    /** 默认矿石标签（含对应深板岩变体，与 MineTask 一致） */
    private static final String[] ORE_TAGS = {
            "coal_ores", "copper_ores", "iron_ores", "gold_ores", "redstone_ores",
            "lapis_ores", "diamond_ores", "emerald_ores", "quartz_ores"
    };

    private PerceptionBlockUtil() {
    }

    // ---------- 方块判定 ----------

    /** 是否为矿石（命中任一矿石标签，26.2 已移除 OreBlock） */
    public static boolean isOre(BlockState state) {
        for (String tag : ORE_TAGS) {
            if (state.is(TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("minecraft", tag)))) {
                return true;
            }
        }
        return false;
    }

    /** 是否为成熟作物（可收获） */
    public static boolean isMatureCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    /** 是否为危险方块（岩浆/火/岩浆块/水/仙人掌），L0 安全层相关 */
    public static boolean isDangerBlock(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() == Blocks.LAVA
                || state.getBlock() == Blocks.FIRE
                || state.getBlock() == Blocks.MAGMA_BLOCK
                || state.getBlock() == Blocks.WATER
                || state.getBlock() == Blocks.CACTUS;
    }

    // ---------- 容器查找 ----------

    /** 该位置是否为容器（箱子/熔炉/漏斗等 Container 方块实体） */
    public static boolean isContainerAt(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container;
    }

    /** 在 center 周围 range 内找最近的容器（ChestOpenTask 逻辑参数化） */
    public static BlockPos findNearestContainer(Level level, BlockPos center, int range) {
        BlockPos a = center.offset(-range, -2, -range);
        BlockPos b = center.offset(range, 2, range);
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(a, b)) {
            if (isContainerAt(level, p)) {
                double d = p.distSqr(center);
                if (d < best) {
                    best = d;
                    nearest = p.immutable();
                }
            }
        }
        return nearest;
    }

    /** 在 center 周围 range 内找最近的熔炉（SmeltTask 逻辑参数化） */
    public static BlockPos findNearestFurnace(Level level, BlockPos center, int range) {
        return BlockPos.betweenClosedStream(center.offset(-range, -range, -range), center.offset(range, range, range))
                .filter(pos -> level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity)
                .map(BlockPos::immutable)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(center)))
                .orElse(null);
    }

    // ---------- 实体判定 ----------

    /** 找周围最近敌对生物（统一走 {@link MaidTargetFilter}：Monster + 排除表 + 女仆可见 + 非友军） */
    public static LivingEntity findNearestHostile(SmartMaidEntity maid, int range) {
        return com.oyxdsg.smartmaid.entity.ai.MaidActions.findEntities(maid, LivingEntity.class, range,
                e -> MaidTargetFilter.isHostile(maid, e))
                .stream()
                .min(Comparator.comparingDouble(e -> maid.distanceToSqr(e)))
                .orElse(null);
    }

    /**
     * 找周围最近的**指定实体类型**生物（排除自己 / 主人 / 玩家）。
     *
     * <p>用途：{@code attack} 指定目标（如「杀这头猪」→ {@code target=minecraft:pig}）。
     * 与 {@link #findNearestHostile} 共用同一套可行性判定（存活 / canAttack / 视线），
     * 但把「Monster」替换为「类型精确匹配」，因此也可用于被动生物（获取食物）。</p>
     */
    public static LivingEntity findNearestByType(SmartMaidEntity maid, int range, EntityType<?> type) {
        if (type == null) {
            return null;
        }
        return com.oyxdsg.smartmaid.entity.ai.MaidActions.findEntities(maid, LivingEntity.class, range,
                e -> e.getType() == type && e.isAlive() && e != maid
                        && e != maid.getOwner() && !(e instanceof Player)
                        && maid.canAttack(e) && maid.hasLineOfSight(e))
                .stream()
                .min(Comparator.comparingDouble(e -> maid.distanceToSqr(e)))
                .orElse(null);
    }
}
