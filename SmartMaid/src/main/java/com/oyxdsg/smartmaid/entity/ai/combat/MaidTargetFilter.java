package com.oyxdsg.smartmaid.entity.ai.combat;

import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;

/**
 * 战斗目标过滤：敌对判定排除表 + 友军判定。
 *
 * <p>设计（见 DEVELOPMENT_COMBAT.md §5.7）：默认只把 {@link Monster} 视为敌对，
 * 但末影人 / 僵尸猪人属于中立（除非主动攻击主人）；<b>友军只指玩家</b>——
 * 女仆绝不主动攻击、也绝不伤害玩家。</p>
 */
public final class MaidTargetFilter {

    private MaidTargetFilter() {
    }

    /**
     * 是否可主动攻击（索敌用）：敌对 + 女仆可见 + 非友军 + 未点名排除。
     */
    public static boolean isHostile(SmartMaidEntity maid, LivingEntity target) {
        if (target == null || target == maid || !target.isAlive()) {
            return false;
        }
        if (isFriendly(target)) {
            return false;
        }
        if (target instanceof EnderMan || target instanceof ZombifiedPiglin) {
            return false;
        }
        return target instanceof Monster && maid.canAttack(target) && maid.hasLineOfSight(target);
    }

    /** 是否为友军：只指玩家（女仆绝不伤害玩家）。 */
    public static boolean isFriendly(Entity entity) {
        return entity instanceof Player;
    }
}
