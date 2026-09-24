package com.oyxdsg.smartmaid.entity.ai.combat;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidActions;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * 远程弓箭（仿玩家；见 DEVELOPMENT_COMBAT.md §5.3）。
 *
 * <p>原版 {@code BowItem.releaseUsing} 首行就 {@code if (!(entity instanceof Player)) return false}，
 * 女仆不能用，故这里复制玩家的蓄力/发射流程自实现：选弓（只比 POWER 附魔）→ 校验箭 →
 * {@code startUsingItem} 蓄力 → 满蓄力发射 {@link MaidArrow}（不伤玩家）→ 消耗箭 + 弓耐久。</p>
 */
public final class MaidRangedSkill {

    /** 箭的重力加速度（用于简单弹道抬升补偿） */
    private static final double ARROW_GRAVITY = 0.05D;
    /** 原版箭基线伤害（AbstractArrow 默认值，26.2 无公开 getter） */
    private static final double ARROW_BASE_DAMAGE = 2.0D;

    private MaidRangedSkill() {
    }

    /** 是否有弓且有箭。 */
    public static boolean canShoot(SmartMaidEntity maid) {
        int bowSlot = findBestBowSlot(maid);
        return bowSlot >= 0 && findArrowSlot(maid, maid.getMaidInventory().getItem(bowSlot)) >= 0;
    }

    /** 开始拉弓：换最优弓到主手并 startUsingItem。 */
    public static boolean beginDraw(SmartMaidEntity maid) {
        int bowSlot = findBestBowSlot(maid);
        if (bowSlot < 0 || !MaidActions.swapToMainhand(maid, bowSlot)) {
            return false;
        }
        ItemStack bow = maid.getMainHandItem();
        if (!(bow.getItem() instanceof BowItem) || findArrowSlot(maid, bow) < 0) {
            return false;
        }
        maid.startUsingItem(InteractionHand.MAIN_HAND);
        return true;
    }

    /** 当前蓄力（0..1）。 */
    public static float drawPower(SmartMaidEntity maid) {
        return BowItem.getPowerForTime(maid.getTicksUsingItem());
    }

    /** 释放（自写发射）：创建 MaidArrow、瞄准、施速、消耗箭/耐久。 */
    public static boolean release(SmartMaidEntity maid, LivingEntity target) {
        if (!(maid.level() instanceof ServerLevel level)) {
            maid.stopUsingItem();
            return false;
        }
        ItemStack bow = maid.getMainHandItem();
        if (!(bow.getItem() instanceof BowItem)) {
            maid.stopUsingItem();
            return false;
        }
        int arrowSlot = findArrowSlot(maid, bow);
        if (arrowSlot < 0) {
            maid.stopUsingItem();
            return false;
        }
        float power = BowItem.getPowerForTime(maid.getTicksUsingItem());
        if (power < 0.1F) {
            maid.stopUsingItem();
            return false;
        }

        SimpleContainer inv = maid.getMaidInventory();
        ItemStack arrowStack = inv.getItem(arrowSlot);
        MaidArrow arrow = new MaidArrow(level, maid, bow, arrowStack.copyWithCount(1));
        arrow.setCritArrow(power >= 1.0F);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;

        int powerEnch = powerLevel(level, bow);
        if (powerEnch > 0) {
            // 原版箭基线伤害 2.0（AbstractArrow 默认），力量附魔每级 +0.5，首级额外 +0.5
            arrow.setBaseDamage(ARROW_BASE_DAMAGE + powerEnch * 0.5D + 0.5D);
        }
        arrow.setPos(maid.getX(), maid.getEyeY() - 0.1D, maid.getZ());
        Vec3 dir = aimDirection(maid, target, power);
        arrow.shoot(dir.x, dir.y, dir.z, power * 3.0F, 1.0F);
        level.addFreshEntity(arrow);

        arrowStack.shrink(1);
        bow.hurtAndBreak(1, maid, EquipmentSlot.MAINHAND);
        maid.stopUsingItem();
        MaidDebug.log("Combat shoot power=" + MaidDebug.fmt1(power) + " powerEnch=" + powerEnch
                + " target=" + target.getType().toShortString());
        return true;
    }

    /** 简单弹道：目标位置 + 速度预判 + 重力抬升补偿。 */
    private static Vec3 aimDirection(SmartMaidEntity maid, LivingEntity target, float power) {
        Vec3 eye = maid.getEyePosition();
        Vec3 targetPos = target.getEyePosition();
        double speed = Math.max(0.1D, power * 3.0D);
        double travel = targetPos.distanceTo(eye) / speed;
        Vec3 predicted = targetPos.add(target.getDeltaMovement().scale(travel));
        Vec3 dir = predicted.subtract(eye);
        dir = dir.add(0.0D, 0.5D * ARROW_GRAVITY * travel * travel, 0.0D);
        return dir.normalize();
    }

    /** 找最优弓（只比力量附魔等级；同级取先找到的）；无弓返回 -1。 */
    public static int findBestBowSlot(SmartMaidEntity maid) {
        SimpleContainer inv = maid.getMaidInventory();
        int bestSlot = -1;
        int bestPower = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!(stack.getItem() instanceof BowItem)) {
                continue;
            }
            int power = powerLevel(maid.level(), stack);
            if (power > bestPower) {
                bestPower = power;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /** 找背包里该武器可用的弹药槽；无返回 -1。 */
    public static int findArrowSlot(SmartMaidEntity maid, ItemStack weapon) {
        Predicate<ItemStack> supported = supportedProjectiles(weapon);
        if (supported == null) {
            return -1;
        }
        SimpleContainer inv = maid.getMaidInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (supported.test(inv.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    private static Predicate<ItemStack> supportedProjectiles(ItemStack weapon) {
        return weapon.getItem() instanceof ProjectileWeaponItem weaponItem
                ? weaponItem.getAllSupportedProjectiles() : null;
    }

    /** 力量（POWER）附魔等级；取不到注册表（如异常环境）时返回 0。 */
    private static int powerLevel(Level level, ItemStack bow) {
        Registry<Enchantment> registry = level.registryAccess().lookup(Registries.ENCHANTMENT).orElse(null);
        if (registry == null) {
            return 0;
        }
        Holder<Enchantment> power = registry.getOrThrow(Enchantments.POWER);
        return EnchantmentHelper.getItemEnchantmentLevel(power, bow);
    }
}
