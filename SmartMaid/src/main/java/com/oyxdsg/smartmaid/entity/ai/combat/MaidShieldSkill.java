package com.oyxdsg.smartmaid.entity.ai.combat;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * 盾牌格挡（仿玩家，见 DEVELOPMENT_COMBAT.md §5.4）。
 *
 * <p>把带 {@code BLOCKS_ATTACKS} 组件的物品换到副手槽（40），{@code startUsingItem(OFFHAND)}
 * 后 {@code isBlocking()} 为真，减伤由 {@code LivingEntity.blockUsingItem} 自动处理。
 * 原版举盾还要求已过 {@code BlocksAttacks.blockDelayTicks()}（盾 5 tick），故单次持续
 * 至少 5 tick（战斗里给 10）。</p>
 */
public final class MaidShieldSkill {

    /** 副手槽（与女仆 41 格布局一致） */
    private static final int OFFHAND_SLOT = 40;
    /** 背包区上限（0-35，不含盔甲/副手） */
    private static final int STORAGE_SIZE = 36;

    private MaidShieldSkill() {
    }

    /** 背包（含副手）是否有盾。 */
    public static boolean hasShield(SmartMaidEntity maid) {
        SimpleContainer inv = maid.getMaidInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isShield(inv.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把盾换到副手槽（40）并同步装备栏；副手原有物品与盾所在槽交换（不丢物品）。
     *
     * @return true 表示副手已是盾
     */
    public static boolean equipShield(SmartMaidEntity maid) {
        SimpleContainer inv = maid.getMaidInventory();
        if (isShield(inv.getItem(OFFHAND_SLOT))) {
            maid.syncInventoryArmor();
            return true;
        }
        int slot = -1;
        for (int i = 0; i < STORAGE_SIZE; i++) {
            if (isShield(inv.getItem(i))) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return false;
        }
        ItemStack old = inv.getItem(OFFHAND_SLOT);
        inv.setItem(OFFHAND_SLOT, inv.getItem(slot));
        inv.setItem(slot, old);
        inv.setChanged();
        maid.syncInventoryArmor();
        return true;
    }

    /** 开始举盾（副手使用中）。若正使用其它手物品，先切过来。 */
    public static boolean beginBlock(SmartMaidEntity maid) {
        if (!isShield(maid.getMaidInventory().getItem(OFFHAND_SLOT))) {
            return false;
        }
        if (maid.isUsingItem()) {
            maid.stopUsingItem();
        }
        maid.startUsingItem(InteractionHand.OFF_HAND);
        return true;
    }

    private static boolean isShield(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.BLOCKS_ATTACKS);
    }
}
