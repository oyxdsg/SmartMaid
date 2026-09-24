package com.oyxdsg.smartmaid.entity.ai;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.Vec3;

/**
 * 移动调试监测：同时输出玩家与女仆的坐标/按键状态 + 各自周围地形 ASCII 俯视图。
 *
 * <p>用于对比"玩家在哪个坐标按了什么键"与"女仆在哪个坐标按了什么键"，
 * 以及周围地形（墙/沟/平台），精确定位移动差异。每 20 tick（1 秒）输出一次。</p>
 *
 * <p>方块字符：{@code #}满高实心  {@code +}半高(半砖/台阶)  {@code -}低矮(地毯等)
 * {@code ~}水  {@code L}岩浆/火  {@code .}空气/可走  {@code @}实体位置</p>
 */
public final class MaidMonitor {

    /** 玩家周围地形半径（格），21x21 覆盖 20 格宽 */
    private static final int PLAYER_R = 10;
    /** 女仆周围地形半径（格） */
    private static final int MAID_R = 6;

    private MaidMonitor() {
    }

    public static void tick(SmartMaidEntity maid) {
        // 调试输出整体高噪（状态行 + 地形俯视图），全部由 MaidDebug.verbose() 单独开启：
        // 默认不打，避免小模组持续污染日志；移动/寻路排查时 MaidDebug.VERBOSE 改 true。
        if (!MaidDebug.verbose()) {
            return;
        }
        // 低频：每 100 tick（5s）一行状态
        if (maid.tickCount % 100 != 0) {
            return;
        }
        Level level = maid.level();
        if (level.isClientSide()) {
            return;
        }

        LivingEntity owner = maid.getOwner();
        if (owner instanceof Player player) {
            logEntityState("PLAYER", player);
            logTerrain(level, player.blockPosition(), "PLAYER", PLAYER_R, player);
        }
        logEntityState("MAID", maid);
        logTerrain(level, maid.blockPosition(), "MAID", MAID_R, maid);
    }

    /** 输出实体状态：坐标 + 移动输入（按键） + 速度 */
    private static void logEntityState(String tag, LivingEntity e) {
        MaidDebug.log(tag + " pos=(" + fmt(e.getX()) + "," + fmt(e.getY()) + "," + fmt(e.getZ())
                + " onGround=" + e.onGround()
                + " sprint=" + e.isSprinting()
                + " jump=" + e.isJumping()
                + " zza=" + fmt(e.zza)
                + " xxa=" + fmt(e.xxa)
                + " yRot=" + fmt(e.getYRot())
                + " vel=(" + fmt(e.getDeltaMovement().x) + "," + fmt(e.getDeltaMovement().y) + "," + fmt(e.getDeltaMovement().z) + ")");
    }

    /** 输出实体周围地形 ASCII 俯视图（实体所在 y 层） */
    private static void logTerrain(Level level, BlockPos center, String tag, int r, LivingEntity self) {
        MaidDebug.log("terrain[" + tag + "] @ (" + center.getX() + "," + center.getY() + "," + center.getZ()
                + ") R=" + r + " (x:" + (center.getX() - r) + ".." + (center.getX() + r)
                + " z:" + (center.getZ() - r) + ".." + (center.getZ() + r) + ")");
        int selfX = self.blockPosition().getX() - center.getX();
        int selfZ = self.blockPosition().getZ() - center.getZ();
        for (int dz = r; dz >= -r; dz--) {
            StringBuilder row = new StringBuilder();
            for (int dx = -r; dx <= r; dx++) {
                if (dx == selfX && dz == selfZ) {
                    row.append('@');
                } else {
                    row.append(charFor(level, center.offset(dx, 0, dz)));
                }
            }
            MaidDebug.log("  " + row);
        }
    }

    private static char charFor(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return '.';
        }
        if (state.getFluidState().typeHolder().is(FluidTags.WATER)) {
            return '~';
        }
        if (state.getFluidState().typeHolder().is(FluidTags.LAVA) || state.getBlock() == Blocks.FIRE) {
            return 'L';
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return '.';
        }
        double maxY = shape.max(Direction.Axis.Y);
        if (maxY >= 0.95D) {
            return '#';
        }
        if (maxY >= 0.4D) {
            return '+';
        }
        return '-';
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
