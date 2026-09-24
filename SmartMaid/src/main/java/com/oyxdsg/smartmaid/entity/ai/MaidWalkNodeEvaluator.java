package com.oyxdsg.smartmaid.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * 女仆专用寻路评估器：在保留原版逻辑基础上：
 * <ul>
 *     <li>允许"可跳跃跨越"的垂直路径：上坡 1.25~1.5 格、下坡 1.5~3 格</li>
 *     <li>生成"跨沟"邻居：当前格水平 2~4 格、中间各格无支撑、对岸可站立 → 连边，
 *         配合 {@link MaidActionExecutor} 执行跳越（否则寻路永远不会产生"沟对岸"路径节点）</li>
 * </ul>
 */
public class MaidWalkNodeEvaluator extends WalkNodeEvaluator {

    private static final double MAX_UP_STEP = 1.5D;
    private static final double MAX_DOWN_DROP = 3.0D;
    private static final int GAP_MIN_DIST = 2;
    private static final int GAP_MAX_DIST = 4;

    @Override
    protected boolean isNeighborValid(Node currentNode, Node neighbor) {
        if (super.isNeighborValid(currentNode, neighbor)) {
            return true;
        }
        if (neighbor.closed || neighbor.type == PathType.BLOCKED) {
            return false;
        }
        double floorCurrent = this.getFloorLevel(new BlockPos(currentNode.x, currentNode.y, currentNode.z));
        double floorNeighbor = this.getFloorLevel(new BlockPos(neighbor.x, neighbor.y, neighbor.z));
        double diff = floorNeighbor - floorCurrent;
        if (diff > 1.25D && diff <= MAX_UP_STEP) {
            return true;
        }
        if (diff < -1.5D && diff >= -MAX_DOWN_DROP) {
            return true;
        }
        return false;
    }

    @Override
    public int getNeighbors(Node[] nodeArray, Node node) {
        int count = super.getNeighbors(nodeArray, node);
        return this.addGapJumpNeighbors(nodeArray, node, count);
    }

    /**
     * 在原版相邻邻居之外，额外生成"跨沟对岸"邻居：当前格沿 4 主轴方向 2~4 格、
     * 中间各格均为无支撑空气、对岸格可站立（支持对岸比当前高 1 格的高台）。
     * 每方向只取最短可行跨沟。
     */
    private int addGapJumpNeighbors(Node[] nodeArray, Node node, int count) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (count >= nodeArray.length - 1) {
                break;
            }
            int sx = dir.getStepX();
            int sz = dir.getStepZ();
            for (int d = GAP_MIN_DIST; d <= GAP_MAX_DIST; d++) {
                if (count >= nodeArray.length - 1) {
                    break;
                }
                if (!this.isGapPath(node.x, node.y, node.z, sx, sz, d)) {
                    continue;
                }
                // 对岸落点：先试同层，再试 +1 层（对岸高 1 格的高台）
                for (int up = 0; up <= 1; up++) {
                    BlockPos to = new BlockPos(node.x + sx * d, node.y + up, node.z + sz * d);
                    if (!this.isLandableCell(to)) {
                        continue;
                    }
                    double floor = this.getFloorLevel(to);
                    Node n = this.findAcceptedNode(to.getX(), to.getY(), to.getZ(), 1, floor, dir, node.type);
                    if (n == null || n.closed || n.type == PathType.BLOCKED) {
                        continue;
                    }
                    if (this.containsNode(nodeArray, count, n)) {
                        continue;
                    }
                    nodeArray[count++] = n;
                    break;
                }
            }
        }
        return count;
    }

    /** 沿主轴从当前格到目标格，中间各格均为"沟"（身体空气 + 下方无支撑） */
    private boolean isGapPath(int x, int y, int z, int sx, int sz, int dist) {
        for (int i = 1; i < dist; i++) {
            BlockPos mid = new BlockPos(x + sx * i, y, z + sz * i);
            if (!this.isGapCell(mid)) {
                return false;
            }
        }
        return true;
    }

    private boolean isGapCell(BlockPos p) {
        BlockState body = this.currentContext.getBlockState(p);
        BlockState below = this.currentContext.getBlockState(p.below());
        return body.isAir() && below.isAir();
    }

    /** 对岸格可站立：身体空气、脚下有支撑、头顶净空 */
    private boolean isLandableCell(BlockPos to) {
        BlockState body = this.currentContext.getBlockState(to);
        BlockState below = this.currentContext.getBlockState(to.below());
        BlockState above = this.currentContext.getBlockState(to.above());
        return body.isAir() && !below.isAir() && above.isAir();
    }

    private boolean containsNode(Node[] arr, int n, Node node) {
        for (int i = 0; i < n; i++) {
            Node o = arr[i];
            if (o != null && o.x == node.x && o.y == node.y && o.z == node.z) {
                return true;
            }
        }
        return false;
    }
}
