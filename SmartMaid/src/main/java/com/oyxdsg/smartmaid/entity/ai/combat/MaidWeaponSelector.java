package com.oyxdsg.smartmaid.entity.ai.combat;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

/**
 * 近战武器评分与选择：直接从物品属性修饰符解析「单次伤害 × 攻击速度」。
 *
 * <p>26.2 已移除 Tier / DiggerItem，武器数值全部来自 {@code DataComponents.ATTRIBUTE_MODIFIERS}；
 * 用 {@link ItemStack#forEachModifier} 取主手槽的 ATTACK_DAMAGE / ATTACK_SPEED 修饰符，
 * 叠加女仆自身基线（{@link AttributeInstance#getBaseValue()}）得出 DPS。</p>
 */
public final class MaidWeaponSelector {

    /** 攻击冷却下限 / 上限（tick），避免极快或极慢武器破坏节奏 */
    private static final int MIN_ATTACK_COOLDOWN = 14;
    private static final int MAX_ATTACK_COOLDOWN = 26;

    private MaidWeaponSelector() {
    }

    /** 该物品作为近战武器的 DPS 评分；0 表示不可作为武器（空物品 / 无伤害无攻速）。 */
    public static double meleeScore(SmartMaidEntity maid, ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        double[] bonus = modifiers(stack);
        if (bonus[0] <= 0.0D && bonus[1] <= 0.0D) {
            return 0.0D;
        }
        double damage = baseValue(maid, Attributes.ATTACK_DAMAGE) + bonus[0];
        double speed = attackSpeed(maid, stack);
        return Math.max(0.0D, damage) * speed;
    }

    /** 当前主手武器对应的攻击冷却（tick）：20 / 攻击速度，clamp[14,26]。 */
    public static int meleeCooldownTicks(SmartMaidEntity maid) {
        double speed = attackSpeed(maid, maid.getMaidInventory().getItem(0));
        int ticks = (int) Math.round(20.0D / speed);
        return Mth.clamp(ticks, MIN_ATTACK_COOLDOWN, MAX_ATTACK_COOLDOWN);
    }

    /** 从背包（含主手）挑 DPS 最高的近战武器换到主手（槽 0）；无可用武器则保持现状。 */
    public static void equipBestMelee(SmartMaidEntity maid) {
        SimpleContainer inv = maid.getMaidInventory();
        int bestSlot = -1;
        double bestScore = 0.0D;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            double score = meleeScore(maid, inv.getItem(i));
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestSlot < 0 || bestSlot == 0) {
            return;
        }
        ItemStack hand = inv.getItem(0);
        inv.setItem(0, inv.getItem(bestSlot));
        inv.setItem(bestSlot, hand);
        inv.setChanged();
        maid.syncInventoryArmor();
        MaidDebug.log("Combat equipMelee: 槽 " + bestSlot + " -> 主手 " + MaidDebug.itemName(inv.getItem(0)));
    }

    private static double attackSpeed(SmartMaidEntity maid, ItemStack stack) {
        double base = baseValue(maid, Attributes.ATTACK_SPEED);
        double bonus = stack.isEmpty() ? 0.0D : modifiers(stack)[1];
        return Math.max(0.1D, base + bonus);
    }

    /** 返回 {伤害加成, 攻速加成}。 */
    private static double[] modifiers(ItemStack stack) {
        double[] bonus = {0.0D, 0.0D};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.is(Attributes.ATTACK_DAMAGE)) {
                bonus[0] += modifier.amount();
            } else if (attribute.is(Attributes.ATTACK_SPEED)) {
                bonus[1] += modifier.amount();
            }
        });
        return bonus;
    }

    private static double baseValue(SmartMaidEntity maid, Holder<Attribute> attribute) {
        AttributeInstance instance = maid.getAttribute(attribute);
        return instance != null ? instance.getBaseValue() : 0.0D;
    }
}
