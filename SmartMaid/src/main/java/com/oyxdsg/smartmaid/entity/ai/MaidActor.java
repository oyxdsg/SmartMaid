package com.oyxdsg.smartmaid.entity.ai;

import com.mojang.authlib.GameProfile;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

/**
 * 女仆的"动作执行身份"—— 以主人身份运行的 FakePlayer（整合包兼容改造 2026-09-29）。
 *
 * <p><b>为什么需要</b>：整合包里大量 mod 的方块/物品实现会直接访问 {@code player}
 * （判断潜行、取 UUID 做归属、查领地权限、记统计）。原先女仆破坏/放置方块时
 * 传 {@code null} 玩家或绕过原版流程，导致：mod 侧 NPE 崩溃、领地保护拦不住、
 * 机器/容器的 {@code playerWillDestroy} 数据保存钩子不执行。</p>
 *
 * <p><b>为什么用主人的 GameProfile 而不是默认 UUID</b>：FakePlayer 的身份决定了
 * 领地保护、权限判定、方块交互"被当成谁"。用主人的 profile 时，这些判定全部按
 * 主人走 —— 语义正确（女仆是主人的手），主人自己的领地内女仆可正常干活，
 * 别人的领地则会被正常拒绝。用默认 UUID 反而会被当成一个陌生玩家。</p>
 *
 * <p><b>位置必须同步</b>：区域/领地类判定依赖实体坐标，所以每次取用都把 actor
 * 挪到女仆所在处，否则 mod 会在 (0,0,0) 附近做判定。</p>
 *
 * <p>线程：只在服务端逻辑里调用（{@code FakePlayer.get} 是服务端操作）。</p>
 */
public final class MaidActor {

    private MaidActor() {
    }

    /**
     * 取一个"以主人身份、位于女仆当前位置"的动作执行者。
     *
     * @return 服务端 FakePlayer；不在服务端时返回 {@code null}
     */
    public static ServerPlayer actorFor(SmartMaidEntity maid) {
        if (!(maid.level() instanceof ServerLevel level)) {
            return null;
        }
        ServerPlayer actor = FakePlayer.get(level, ownerProfile(maid));
        // 位置/朝向同步：领地与区域判定依赖坐标
        actor.setPos(maid.getX(), maid.getY(), maid.getZ());
        actor.setYRot(maid.getYRot());
        actor.setXRot(maid.getXRot());
        // 手持同步：mod 的 useOn/place 实现常直接读 player.getItemInHand()，
        // 不同步的话第三方方块会看到"空手"而行为异常。这里放的是同一个 ItemStack 引用，
        // 所以 mod 对物品的消耗会正确反映到女仆手上。
        actor.setItemInHand(InteractionHand.MAIN_HAND, maid.getMainHandItem());
        actor.setItemInHand(InteractionHand.OFF_HAND, maid.getOffhandItem());
        return actor;
    }

    /** 优先用主人的身份；主人离线/无主人时退化为女仆自身 UUID 的合成身份。 */
    private static GameProfile ownerProfile(SmartMaidEntity maid) {
        LivingEntity owner = maid.getOwner();
        if (owner instanceof ServerPlayer sp) {
            return sp.getGameProfile();
        }
        return new GameProfile(maid.getUUID(), "SmartMaid");
    }
}
