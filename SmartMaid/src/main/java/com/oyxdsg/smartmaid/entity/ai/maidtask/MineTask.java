package com.oyxdsg.smartmaid.entity.ai.maidtask;

import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidBlockBreaker;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionBlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 挖矿任务（M3）：换镐 → 区域搜矿物 → 有挖掘过程的挖矿（进度裂纹 + 按硬度耗时）→
 * 掉落物直接进背包，count 达成或区域无矿结束。
 *
 * <p>26.2 移除了 OreBlock（已反编译确认），矿物判定改用矿石标签
 * （coal_ores / iron_ores / gold_ores / diamond_ores 等，均存在于原版数据包）。</p>
 */
public class MineTask extends MaidAITask {

    /** 垂直扫描范围（格）：比水平小，避免 y 方向扫太深/太高 */
    private static final int Y_RANGE = 8;

    private final BlockPos center;
    private final int range;
    private final int count;
    private int mined;
    private BlockPos current;
    private boolean noOre;
    /** 已被判定「被遮挡挖不到」的矿位（跳过，不再反复尝试） */
    private final Set<BlockPos> skipped = new HashSet<>();

    // 挖掘执行器（复用 MaidBlockBreaker：走到旁 → 裂纹耗时 → 破坏进背包）
    private final MaidBlockBreaker breaker = new MaidBlockBreaker();

    public MineTask(BlockPos center, int range, int count) {
        super("mine");
        this.center = center;
        this.range = range;
        this.count = Math.max(1, count);
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return this.center != null;
    }

    @Override
    public void start(SmartMaidEntity maid) {
        this.mined = 0;
        this.current = null;
        this.noOre = false;
        this.skipped.clear();
        // 换镐：背包找镐类工具；没有镐则徒手继续（不阻塞任务）
        MaidActions.equipFromBackpack(maid, stack -> stack.is(Items.DIAMOND_PICKAXE)
                || stack.is(Items.IRON_PICKAXE) || stack.is(Items.STONE_PICKAXE)
                || stack.is(Items.WOODEN_PICKAXE) || stack.is(Items.GOLDEN_PICKAXE)
                || stack.is(Items.NETHERITE_PICKAXE));
        MaidDebug.log("Mine start center=" + center + " range=" + range + " count=" + count);
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        if (this.mined >= this.count || this.noOre) {
            return;
        }
        // 找下一个矿并交给挖掘执行器
        if (!this.breaker.isActive()) {
            if (this.current == null || maid.level().getBlockState(this.current).isAir()) {
                this.current = findNearestOre(maid);
                if (this.current == null) {
                    this.noOre = true;
                    return;
                }
            }
            if (!this.breaker.begin(maid, this.current)) {
                // 不可破坏方块 → 跳过，下次 tick 找下一个矿
                this.current = null;
                return;
            }
        }
        // 每 tick 驱动挖掘（含寻路/裂纹/破坏），完成后计数
        if (this.breaker.tick(maid)) {
            this.mined++;
            this.current = null;
        } else if (!this.breaker.isActive()) {
            // 挖掘被中止（目标被遮挡挖不到 / 中断）→ 记入跳过，换下一个矿
            this.skipped.add(this.current);
            this.current = null;
        }
    }

    @Override
    public boolean isDone() {
        return this.mined >= this.count || this.noOre;
    }

    @Override
    public String result() {
        // 有矿但一块没挖到（够不到 / 没方块搭高 / 被挡）→ 如实回报失败，不假报"完成"
        if (this.mined == 0 && !this.skipped.isEmpty()) {
            return "没挖到矿（够不到或无法搭高）";
        }
        return "已挖 " + this.mined + " 个矿物";
    }

    /** 结构化结果（脚本变量绑定用：`mine as ore` → $ore.collected）。 */
    @Override
    public JsonObject resultJson() {
        JsonObject out = new JsonObject();
        out.addProperty("collected", this.mined);
        out.addProperty("target", this.count);
        return out;
    }

    @Override
    public void forceStop(SmartMaidEntity maid) {
        this.breaker.abort(maid); // 清理裂纹并复位
    }

    /** 区域内找最近的、尚未判定挖不到的矿石方块。
     *  视线遮挡判定在女仆**走到矿旁后**由挖掘执行器做（对齐玩家：准星射线被挡就点不到矿）——
     *  远处视角会误判，先走过去再说。 */
    private BlockPos findNearestOre(SmartMaidEntity maid) {
        return scanArea(maid).stream()
                .filter(p -> !this.skipped.contains(p))
                .min(Comparator.comparingDouble(p -> p.distSqr(maid.blockPosition())))
                .orElse(null);
    }

    /** 区域扫描：返回区域内所有矿石位置。水平 range、垂直 Y_RANGE（比水平小）。 */
    private List<BlockPos> scanArea(SmartMaidEntity maid) {
        Level level = maid.level();
        List<BlockPos> ores = new ArrayList<>();
        BlockPos a = this.center.offset(-this.range, -Y_RANGE, -this.range);
        BlockPos b = this.center.offset(this.range, Y_RANGE, this.range);
        for (BlockPos pos : BlockPos.betweenClosed(a, b)) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && isOre(state)) {
                ores.add(pos.immutable());
            }
        }
        return ores;
    }

    /** 判定方块是否为矿石（命中任一矿石标签）。实现已收拢到感知工具 {@link PerceptionBlockUtil}。 */
    public static boolean isOre(BlockState state) {
        return PerceptionBlockUtil.isOre(state);
    }
}
