package com.oyxdsg.smartmaid.entity.ai.perception.sense;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.perception.MaidPerception;
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionUtil;
import com.oyxdsg.smartmaid.entity.ai.perception.Sense;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 背包感知通道：女仆 41 格背包（0-8 热键 / 9-35 背包 / 36-39 盔甲 / 40 副手）的摘要
 * + 语义能力摘要（capabilities，供 AI 快速判断"我现在能做什么"）。
 *
 * <p>interval 20（1s）。区块 {@code inventory}：</p>
 * <ul>
 *     <li>slots[]：非空槽（摘要式，含耐久/附魔/自定义名）</li>
 *     <li>free_slots：空槽数量</li>
 *     <li>capabilities：has_sword/pickaxe/axe/shovel/hoe（物品 tag 判定，26.2 已移除工具类）/
 *         has_food/has_block/has_torch/food_count/tools[]（各类最好工具）</li>
 * </ul>
 */
public class InventorySense implements Sense {

    @Override
    public int intervalTicks() {
        return 20;
    }

    @Override
    public String sectionName() {
        return "inventory";
    }

    @Override
    public void collect(SmartMaidEntity maid, MaidPerception data) {
        SimpleContainer inv = maid.getMaidInventory();
        JsonObject inventory = data.inventory();

        JsonArray slots = new JsonArray();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject slot = new JsonObject();
            slot.addProperty("i", i);
            PerceptionUtil.writeItem(slot, stack, true);
            slots.add(slot);
        }
        inventory.add("slots", slots);

        int free = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) {
                free++;
            }
        }
        inventory.addProperty("free_slots", free);

        // 语义能力摘要
        JsonObject cap = new JsonObject();
        cap.addProperty("has_sword", hasTag(inv, ItemTags.SWORDS));
        cap.addProperty("has_pickaxe", hasTag(inv, ItemTags.PICKAXES));
        cap.addProperty("has_axe", hasTag(inv, ItemTags.AXES));
        cap.addProperty("has_shovel", hasTag(inv, ItemTags.SHOVELS));
        cap.addProperty("has_hoe", hasTag(inv, ItemTags.HOES));
        cap.addProperty("has_food", findFirst(inv, MaidActions::isFood) != null);
        cap.addProperty("has_block", findFirst(inv, s -> s.getItem() instanceof BlockItem) != null);
        cap.addProperty("has_torch", findFirst(inv, InventorySense::isTorch) != null);
        cap.addProperty("food_count", countFood(inv));
        JsonArray tools = new JsonArray();
        bestTool(tools, "sword", inv, ItemTags.SWORDS);
        bestTool(tools, "pickaxe", inv, ItemTags.PICKAXES);
        bestTool(tools, "axe", inv, ItemTags.AXES);
        bestTool(tools, "shovel", inv, ItemTags.SHOVELS);
        bestTool(tools, "hoe", inv, ItemTags.HOES);
        cap.add("tools", tools);
        inventory.add("capabilities", cap);
    }

    private static boolean hasTag(SimpleContainer inv, TagKey<Item> tag) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(tag)) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack findFirst(SimpleContainer inv, java.util.function.Predicate<ItemStack> predicate) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return stack;
            }
        }
        return null;
    }

    private static int countFood(SimpleContainer inv) {
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && MaidActions.isFood(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean isTorch(ItemStack stack) {
        return stack.is(Items.TORCH) || stack.is(Items.SOUL_TORCH)
                || stack.is(Items.LANTERN) || stack.is(Items.SOUL_LANTERN)
                || stack.is(Items.REDSTONE_TORCH);
    }

    /** 各类工具中剩余耐久最高的一把（写入 tools 数组） */
    private static void bestTool(JsonArray tools, String type, SimpleContainer inv, TagKey<Item> tag) {
        ItemStack best = null;
        int bestRemaining = Integer.MIN_VALUE;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !stack.is(tag)) {
                continue;
            }
            int remaining = stack.getMaxDamage() - stack.getDamageValue();
            if (best == null || remaining > bestRemaining) {
                best = stack;
                bestRemaining = remaining;
            }
        }
        if (best != null) {
            JsonObject o = new JsonObject();
            o.addProperty("type", type);
            o.addProperty("id", PerceptionUtil.itemId(best));
            o.addProperty("remaining", bestRemaining);
            o.addProperty("max_damage", best.getMaxDamage());
            tools.add(o);
        }
    }
}
