package com.oyxdsg.smartmaid.entity.ai.structure;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个结构连通组件：沿 core ∪ support 扩展的 BFS 结果。
 */
public class StructureComponent {

    private final List<BlockPos> cores;
    private final List<BlockPos> supports;

    public StructureComponent(List<BlockPos> cores, List<BlockPos> supports) {
        this.cores = cores;
        this.supports = supports;
    }

    public List<BlockPos> cores() {
        return this.cores;
    }

    public List<BlockPos> supports() {
        return this.supports;
    }

    public int coreCount() {
        return this.cores.size();
    }

    public int supportCount() {
        return this.supports.size();
    }

    public StructureComponent copy() {
        return new StructureComponent(new ArrayList<>(this.cores), new ArrayList<>(this.supports));
    }
}
