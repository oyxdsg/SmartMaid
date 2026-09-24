package com.oyxdsg.smartmaid.entity.ai.structure;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * 矿脉结构类型（结构注册表第二实例）。
 *
 * <p>core = 原版矿石标签（含深板岩变体，与感知判定 PerceptionBlockUtil.ORE_TAGS 一致）；
 * 无 support（单块矿也是结构）。find(structure=ore, produce=#minecraft:iron_ores)
 * 找最近的矿，harvest 沿矿石扩展按 produce 挖矿。</p>
 */
public class OreStructure implements StructureType {

    public static final String ID = "ore";

    private static final String[] ORE_TAGS = {
            "coal_ores", "copper_ores", "iron_ores", "gold_ores", "redstone_ores",
            "lapis_ores", "diamond_ores", "emerald_ores", "quartz_ores"
    };

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Predicate<BlockState> coreBlock() {
        return state -> {
            for (String tag : ORE_TAGS) {
                if (state.is(TagKey.create(Registries.BLOCK,
                        Identifier.fromNamespaceAndPath("minecraft", tag)))) {
                    return true;
                }
            }
            return false;
        };
    }

    @Override
    public Predicate<BlockState> supportBlock() {
        return state -> false;
    }

    @Override
    public boolean isStructure(StructureComponent comp) {
        return comp.coreCount() >= 1;
    }

    /** produce 是否为矿石（如 #minecraft:iron_ores）——用于 harvest 缺 structure 时推断 */
    public static boolean isOreProduce(String produce) {
        return produce != null && produce.contains("_ores");
    }
}
