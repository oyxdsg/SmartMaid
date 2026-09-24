package com.oyxdsg.smartmaid.entity.ai.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.Predicate;

/**
 * 结构类型抽象（Atomic Command Protocol §5.1）：把「世界里的复合结构」（树/作物田/矿脉…）
 * 抽象为统一的 core + support + 结构判定。新增结构只需注册一个实现，不改执行器。
 */
public interface StructureType {

    /** 结构类型 id（find 的 structure 参数，如 "tree"） */
    String id();

    /** 核心块判定（树 = #minecraft:logs）：构成结构的"产出"方块 */
    Predicate<BlockState> coreBlock();

    /** 支撑块判定（树 = #minecraft:leaves）：证明该连通组件是完整结构的"辅助"方块 */
    Predicate<BlockState> supportBlock();

    /** 连通组件是否构成该结构（如树 = core≥1 且 support≥1） */
    boolean isStructure(StructureComponent comp);

    /** 一次 find 扫描允许访问的方块上限（性能护栏，默认 2 万） */
    default int scanBlockLimit() {
        return 20_000;
    }
}
