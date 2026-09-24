package com.oyxdsg.smartmaid.entity.ai.slot;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * 物品槽位抽象（正交 transfer 指令的核心）。
 *
 * <p>女仆相关的所有物品操作统一建模为"从一个槽位把物品移动到另一个槽位"：
 * <code>transfer &lt;from&gt; &lt;to&gt; [count]</code>。</p>
 *
 * <p>槽位类型：</p>
 * <ul>
 *     <li>女仆背包/手持/盔甲：{@code inv:&lt;0-40&gt;}、{@code mainhand}、{@code offhand}、
 *         {@code head/chest/legs/feet}</li>
 *     <li>地面（只作目标）：{@code world:&lt;x&gt;,&lt;y&gt;,&lt;z&gt;}（放置方块 / 丢物品）。
 *         地面→背包的拾取不走 transfer（避免凭空吸物），由 collect/pickup 指令走过去完成</li>
 *     <li>容器（箱子/熔炉等）：{@code container:&lt;x&gt;,&lt;y&gt;,&lt;z&gt;[:&lt;slot|auto&gt;]}</li>
 * </ul>
 */
public interface ItemSlot {

    /** 需要女仆靠近才能操作的位置（world/container），null = 女仆自身背包无需移动 */
    BlockPos reachPos();

    /**
     * 该槽位是否接受此物品（默认接受）。女仆盔甲槽覆写：非对应部位装备返回 false，
     * transfer 会把不接受的物品掉落而不是硬塞进盔甲槽。
     */
    default boolean canAccept(SmartMaidEntity maid, ItemStack stack) {
        return true;
    }

    /**
     * 目标被不同物品占用时是否允许"交换"（女仆自身背包槽允许；容器/地面不允许——
     * 容器满时不应取出箱子内容来交换）。
     */
    default boolean isSwapAllowed() {
        return true;
    }

    /** 查看当前槽位物品（不改变状态） */
    ItemStack peek(SmartMaidEntity maid);

    /** 从槽位提取最多 count 个物品（源操作，会从槽位移除） */
    ItemStack extract(SmartMaidEntity maid, int count);

    /**
     * 把物品放入槽位（目标操作）。能放下则返回空栈；放不下（槽满/占用/放置失败）
     * 返回剩余物品（数量未变）。
     *
     * <p><b>契约</b>：不得修改传入的 {@code stack}（用副本操作），以便调用方判断
     * 是否完全放入、部分放入。</p>
     */
    ItemStack insert(SmartMaidEntity maid, ItemStack stack);
}
