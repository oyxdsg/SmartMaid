package com.oyxdsg.smartmaid.entity.ai.structure;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 树结构类型（结构注册表第一实例）。
 *
 * <p>树定义（Atomic Command Protocol §5.1.1）：一棵树 = 一个连通组件，由
 * core（#minecraft:logs）≥1 且 support（#minecraft:leaves）≥1 构成。
 * 树叶用 tag 判定（天然覆盖全部树叶种类、新增树种自动纳入，不枚举代码）。</p>
 *
 * <p>物种对齐表：{@code species → 对应树叶}，供 {@code find} 在 produce 指定 wood
 * 家族时做严格判定（oak 要 oak_log + oak_leaves 才算橡树）。</p>
 */
public class TreeStructure implements StructureType {

    public static final String ID = "tree";

    private static final TagKey<Block> LOGS = TagKey.create(
            Registries.BLOCK, Identifier.fromNamespaceAndPath("minecraft", "logs"));
    private static final TagKey<Block> LEAVES = TagKey.create(
            Registries.BLOCK, Identifier.fromNamespaceAndPath("minecraft", "leaves"));

    /** 物种 → 树叶方块 id path（仅覆盖有对应原木的物种；杜鹃叶无原木不参与） */
    private static final Map<String, String> SPECIES_LEAVES = new LinkedHashMap<>();

    static {
        SPECIES_LEAVES.put("oak", "oak_leaves");
        SPECIES_LEAVES.put("spruce", "spruce_leaves");
        SPECIES_LEAVES.put("birch", "birch_leaves");
        SPECIES_LEAVES.put("jungle", "jungle_leaves");
        SPECIES_LEAVES.put("acacia", "acacia_leaves");
        SPECIES_LEAVES.put("dark_oak", "dark_oak_leaves");
        SPECIES_LEAVES.put("mangrove", "mangrove_leaves");
        SPECIES_LEAVES.put("cherry", "cherry_leaves");
        SPECIES_LEAVES.put("pale_oak", "pale_oak_leaves");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public java.util.function.Predicate<BlockState> coreBlock() {
        return state -> state.is(LOGS);
    }

    @Override
    public java.util.function.Predicate<BlockState> supportBlock() {
        return state -> state.is(LEAVES);
    }

    @Override
    public boolean isStructure(StructureComponent comp) {
        return comp.coreCount() >= 1 && comp.supportCount() >= 1;
    }

    // ---------- 工具 ----------

    public static boolean isLog(BlockState state) {
        return state.is(LOGS);
    }

    public static boolean isLeaves(BlockState state) {
        return state.is(LEAVES);
    }

    /** 该树叶所属物种（azalea 无原木 → 返回 "azalea"，只宽松用） */
    public static String speciesOfLeaves(BlockState state) {
        Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) {
            return null;
        }
        String path = id.getPath();
        return path.endsWith("_leaves") ? path.substring(0, path.length() - "_leaves".length()) : null;
    }

    /** 该物种的树叶精确 id（如 oak → minecraft:oak_leaves）；无对应树叶返回 null */
    public static Identifier leavesIdOf(String species) {
        String leaves = species == null ? null : SPECIES_LEAVES.get(species);
        return leaves == null ? null : Identifier.fromNamespaceAndPath("minecraft", leaves);
    }
}
