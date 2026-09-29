package com.oyxdsg.smartmaid.entity.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.data.SmartMaidConfig;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 女仆**背包整理**：背包塞不下时，合并同类堆叠 + 丢弃垃圾物品。
 *
 * <p>背景：女仆拾取走原版流程（{@code setCanPickUpLoot} + {@code wantsToPickUp}），
 * 背包 0-35 区塞满后原版判定"没取走"，掉落物留在地上 —— 表现为"砍树掉的木头捡不起来"。
 * 本类在**放不下时**被调用：先把分散的半满堆叠合并（常常就此腾出空槽），
 * 再把垃圾丢出去；确实腾不出空间时，上报一条事件给 AI。</p>
 *
 * <h2>为什么不大改拾取逻辑</h2>
 * <p>不改 {@code wantsToPickUp}（那是"不捡某类物品"的硬编码行为），只在"已经满了"时整理 ——
 * 拾取语义保持原版。</p>
 *
 * <h2>垃圾清单</h2>
 * <p>走数据包 tag {@code #smartmaid:junk}（{@code data/smartmaid/tags/item/junk.json}），
 * 整合包可增删。默认含：各类树苗、腐肉、两种蘑菇、装饰性石头（闪长岩/安山岩/花岗岩/凝灰岩）、
 * 杂草类。**石头（stone）不算垃圾** —— 它是正经建材。</p>
 *
 * <h2>丢弃位置</h2>
 * <p>女仆**身后一格**，并给物品 2 秒拾取冷却 + 让女仆 10 秒内不拾取 —— 否则丢出去
 * 立刻被自己捡回来（她一直在掉落物旁边）。玩家走开就不会捡回。</p>
 */
public final class MaidInventoryTidy {

    /** 垃圾白名单 tag：数据包可覆盖（data/smartmaid/tags/item/junk.json） */
    private static final TagKey<Item> JUNK =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("smartmaid", "junk"));

    /** 背包区（热键 0-8 + 背包 9-35）。盔甲 36-39 / 副手 40 不参与整理 */
    private static final int STORAGE_SLOTS = 36;

    /** 同一条女仆两次"满了但没垃圾"上报的最小间隔（tick） */
    private static final int REPORT_INTERVAL = 600;

    private static final Map<SmartMaidEntity, Integer> LAST_REPORT = new WeakHashMap<>();

    private MaidInventoryTidy() {
    }

    /** 整理结果 */
    public record Result(int merged, int dropped) {
        /** 是否腾出了空间（合并或丢弃任一发生即可能腾出） */
        public boolean freed() {
            return this.merged > 0 || this.dropped > 0;
        }
    }

    /** 是否为垃圾物品（数据包 tag 判定） */
    public static boolean isJunk(ItemStack stack) {
        return !stack.isEmpty() && stack.is(JUNK);
    }

    /** 背包区（0-35）是否已无空槽 */
    public static boolean isStorageFull(SmartMaidEntity maid) {
        SimpleContainer inv = maid.getMaidInventory();
        for (int i = 0; i < STORAGE_SLOTS; i++) {
            if (inv.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 背包塞不下时调用：若已满则整理一次（合并同类 + 丢垃圾）。
     *
     * @return true 表示这次整理有动作（合并过或丢过），调用方应重试放入
     */
    public static boolean tidyIfStuck(SmartMaidEntity maid) {
        if (maid.level().isClientSide() || !SmartMaidConfig.autoTidyWhenFull()) {
            return false;
        }
        if (!isStorageFull(maid)) {
            return false;
        }
        Result r = tidy(maid);
        if (r.freed()) {
            MaidDebug.log("背包整理: 合并 " + r.merged() + " 件 / 丢弃垃圾 " + r.dropped() + " 件");
            return true;
        }
        // 满了、又没有垃圾可丢 → 上报给 AI（带节流，避免每 tick 刷屏）
        reportFull(maid);
        return false;
    }

    /** 整理：合并同类堆叠 → 丢弃全部垃圾。不检查开关/是否满，供测试与手动调用。 */
    public static Result tidy(SmartMaidEntity maid) {
        if (maid.level().isClientSide()) {
            return new Result(0, 0);
        }
        SimpleContainer inv = maid.getMaidInventory();
        int merged = mergeStacks(inv);
        int dropped = dropJunk(maid, inv);
        if (merged > 0 || dropped > 0) {
            inv.setChanged();
        }
        return new Result(merged, dropped);
    }

    /** 把后面的同类堆叠合并进前面的槽，腾出空槽；返回合并的物品总数 */
    private static int mergeStacks(SimpleContainer inv) {
        int merged = 0;
        for (int i = 0; i < STORAGE_SLOTS; i++) {
            ItemStack dst = inv.getItem(i);
            if (dst.isEmpty()) {
                continue;
            }
            int max = dst.getMaxStackSize();
            for (int j = i + 1; j < STORAGE_SLOTS && dst.getCount() < max; j++) {
                ItemStack src = inv.getItem(j);
                if (src.isEmpty() || !ItemStack.isSameItemSameComponents(dst, src)) {
                    continue;
                }
                int move = Math.min(max - dst.getCount(), src.getCount());
                dst.grow(move);
                src.shrink(move);
                merged += move;
                if (src.isEmpty()) {
                    inv.setItem(j, ItemStack.EMPTY);
                }
            }
        }
        return merged;
    }

    /** 丢弃背包区（0-35）里的全部垃圾；返回丢弃的物品总数 */
    private static int dropJunk(SmartMaidEntity maid, SimpleContainer inv) {
        int dropped = 0;
        for (int i = 0; i < STORAGE_SLOTS; i++) {
            ItemStack stack = inv.getItem(i);
            if (!isJunk(stack)) {
                continue;
            }
            dropBehind(maid, stack.copy());
            dropped += stack.getCount();
            inv.setItem(i, ItemStack.EMPTY);
        }
        return dropped;
    }

    /**
     * 把物品丢到女仆**身后一格**（按当前朝向反方向），并加拾取冷却。
     *
     * <p>冷却：物品本身 {@code setPickUpDelay(40)}（2 秒，与原版玩家丢弃一致）；
     * 同时让女仆 10 秒内不拾取 —— 否则她站在原地会把垃圾立刻捡回来。
     * 玩家随后走开，就不会再被捡回。</p>
     */
    public static void dropBehind(SmartMaidEntity maid, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        Level level = maid.level();
        double yaw = Math.toRadians(maid.getYRot());
        // 身后 = 前方向 (-sin, cos) 取反
        double x = maid.getX() + Math.sin(yaw);
        double z = maid.getZ() - Math.cos(yaw);
        ItemEntity item = new ItemEntity(level, x, maid.getY() + 0.25D, z, stack);
        item.setPickUpDelay(40);
        level.addFreshEntity(item);
        maid.suppressPickup(200);
        if (MaidDebug.verbose()) {
            MaidDebug.log("丢弃垃圾 " + MaidDebug.itemName(stack) + " x" + stack.getCount() + " -> 身后");
        }
    }

    /** 上报"背包已满且无垃圾可丢"给 AI（经感知事件通道 → 文件 + WebSocket），带节流 */
    private static void reportFull(SmartMaidEntity maid) {
        int now = maid.tickCount;
        Integer last = LAST_REPORT.get(maid);
        if (last != null && now - last < REPORT_INTERVAL) {
            return;
        }
        LAST_REPORT.put(maid, now);

        JsonObject payload = new JsonObject();
        payload.addProperty("reason", "no_junk");
        payload.addProperty("usedSlots", STORAGE_SLOTS);
        payload.addProperty("freeSlots", 0);
        JsonArray items = new JsonArray();
        SimpleContainer inv = maid.getMaidInventory();
        for (int i = 0; i < STORAGE_SLOTS; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", i);
            entry.addProperty("item", String.valueOf(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())));
            entry.addProperty("count", stack.getCount());
            items.add(entry);
        }
        payload.add("inventory", items);
        maid.getPerceptionModule().reportEvent("inventory_full", payload);
        MaidDebug.log("背包已满且无垃圾可丢，已上报 AI（36/36）");
    }
}
