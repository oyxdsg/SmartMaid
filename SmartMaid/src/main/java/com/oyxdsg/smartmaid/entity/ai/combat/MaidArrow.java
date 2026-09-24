package com.oyxdsg.smartmaid.entity.ai.combat;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 女仆专用箭：继承原版 {@link Arrow}（实体类型仍是 {@code minecraft:arrow}，
 * 客户端走原版渲染，无需注册新实体），但**绝不命中玩家**。
 *
 * <p>原因：原版 {@code AbstractArrow.canHitEntity} 只在 owner 是玩家时才做 PvP 判定，
 * 女仆作为非玩家 owner 射出的箭本可命中玩家。这里硬拦截，落地"女仆无法伤害玩家"规则。
 * 发射应使用本类（见 DEVELOPMENT_COMBAT.md §5.3 / §5.7）。</p>
 */
public class MaidArrow extends Arrow {

    public MaidArrow(Level level, LivingEntity owner, ItemStack weapon, ItemStack ammo) {
        super(level, owner, weapon, ammo);
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        // 友军（玩家自己）免伤；开启"友军伤害"设置后允许命中玩家
        if (target instanceof Player) {
            Entity owner = this.getOwner();
            if (!(owner instanceof SmartMaidEntity maid) || !maid.getSettings().isFriendlyFire()) {
                return false;
            }
        }
        return super.canHitEntity(target);
    }
}
