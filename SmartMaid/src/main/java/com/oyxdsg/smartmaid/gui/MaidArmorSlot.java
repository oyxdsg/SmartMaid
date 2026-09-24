package com.oyxdsg.smartmaid.gui;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 女仆装备槽：行为照搬原版 {@code ArmorSlot}——操作背包容器中的盔甲/副手槽位，
 * 并把物品同步到女仆实体的真实装备栏（EquipmentSlot），保证穿戴/渲染/护甲生效。
 */
public class MaidArmorSlot extends net.minecraft.world.inventory.Slot {
    private final LivingEntity owner;
    private final EquipmentSlot slot;
    private final Identifier emptyIcon;

    public MaidArmorSlot(SmartMaidEntity owner, Container container, EquipmentSlot slot,
                         int index, int x, int y, Identifier emptyIcon) {
        super(container, index, x, y);
        this.owner = owner;
        this.slot = slot;
        this.emptyIcon = emptyIcon;
    }

    @Override
    public void setByPlayer(ItemStack newStack, ItemStack oldStack) {
        if (this.owner != null) {
            this.owner.onEquipItem(this.slot, oldStack, newStack);
        }
        super.setByPlayer(newStack, oldStack);
        this.syncToOwner();
    }

    @Override
    public void set(ItemStack stack) {
        super.set(stack);
        this.syncToOwner();
    }

    @Override
    public int getMaxStackSize() {
        // 盔甲槽单件；副手像玩家一样允许整组堆叠
        if (this.slot == EquipmentSlot.OFFHAND) {
            return super.getMaxStackSize();
        }
        return 1;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        if (this.owner == null) {
            return true;
        }
        // 副手像玩家一样可放任意物品（不要求"可装备在该部位"）
        if (this.slot == EquipmentSlot.OFFHAND) {
            return true;
        }
        return this.owner.isEquippableInSlot(stack, this.slot);
    }

    @Override
    public boolean mayPickup(net.minecraft.world.entity.player.Player player) {
        ItemStack stack = this.getItem();
        if (this.owner != null && !stack.isEmpty() && !player.isCreative()
                && EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
            return false;
        }
        return super.mayPickup(player);
    }

    @Override
    public boolean isActive() {
        return this.owner == null || this.owner.canUseSlot(this.slot);
    }

    @Override
    public Identifier getNoItemIcon() {
        return this.emptyIcon;
    }

    private void syncToOwner() {
        if (this.owner != null) {
            this.owner.setItemSlot(this.slot, this.getItem());
        }
    }
}
