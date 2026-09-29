package com.oyxdsg.smartmaid.entity.ai.combat;

import com.oyxdsg.smartmaid.data.SmartMaidConfig;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;

/**
 * 战斗目标过滤：敌对判定排除表 + 友军判定。
 *
 * <p>设计（见 DEVELOPMENT_COMBAT.md §5.7）：默认只把 {@link Monster} 视为敌对，
 * 但末影人 / 僵尸猪人属于中立（除非主动攻击主人）；<b>友军只指玩家</b>——
 * 女仆绝不主动攻击、也绝不伤害玩家。</p>
 *
 * <p><b>2026-09-29 改造（整合包兼容）</b>：排除表从硬编码 {@code instanceof} 判断
 * 改为「实体类型 tag {@code #smartmaid:never_target} + config 追加」，整合包可以用
 * 数据包声明"这些怪女仆不要打"（剧情怪、守护者、竞技场 NPC 等），无需改模组。</p>
 */
public final class MaidTargetFilter {

    /** 绝不主动攻击的实体类型。数据包可覆盖：data/smartmaid/tags/entity_type/never_target.json */
    private static final TagKey<EntityType<?>> NEVER_TARGET =
            TagKey.create(Registries.ENTITY_TYPE,
                    Identifier.fromNamespaceAndPath("smartmaid", "never_target"));

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
        // 数据包声明的排除表（默认含末影人 / 僵尸猪灵），再叠加 config 追加项
        EntityType<?> type = target.getType();
        if (type.builtInRegistryHolder().is(NEVER_TARGET)) {
            return false;
        }
        if (SmartMaidConfig.combatNeverTarget().contains(type)) {
            return false;
        }
        return target instanceof Monster && maid.canAttack(target) && maid.hasLineOfSight(target);
    }

    /** 是否为友军：只指玩家（女仆绝不伤害玩家）。 */
    public static boolean isFriendly(Entity entity) {
        return entity instanceof Player;
    }
}
