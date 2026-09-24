package com.oyxdsg.smartmaid.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.client.animation.MaidAnimManager;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * 开发调试指令：/maidanim &lt;动作&gt; 强制玩家自己的女仆播放指定动画，用于快速校验动作效果。
 *
 * <p>动作由 {@link SmartMaidEntity#setDebugAnim} 写入 SynchedEntityData 同步到客户端，
 * 客户端 {@code MaidAnimManager.play} 用 PAL 动画控制器播放 Emotecraft 动作 JSON。</p>
 *
 * <p>用法：{@code /maidanim wave} 或 {@code /maidanim 1}；{@code /maidanim none} 清除。</p>
 */
public final class MaidAnimCommand {

    /** 动作名 → id（与 MaidAnimManager.ANIM_NAMES 对应） */
    private static final Map<String, Integer> ANIM_IDS = new HashMap<>();

    static {
        String[] names = MaidAnimManager.getAnimNames();
        for (int i = 0; i < names.length; i++) {
            if (!names[i].isEmpty()) {
                ANIM_IDS.put(names[i], i);
            }
        }
    }

    private MaidAnimCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("maidanim")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> setAnim(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"))))
                .then(Commands.argument("id", IntegerArgumentType.integer(0, 99))
                        .executes(ctx -> setAnimInt(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "id")))));
    }

    private static int setAnim(CommandSourceStack source, String name) {
        Integer id = ANIM_IDS.get(name.toLowerCase());
        if (id == null) {
            source.sendFailure(Component.literal("未知动作: " + name + "（可用: " + ANIM_IDS.keySet() + "）"));
            return 0;
        }
        return setAnimInt(source, id);
    }

    private static int setAnimInt(CommandSourceStack source, int id) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            if (!(player.level() instanceof ServerLevel level)) {
                return 0;
            }
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof SmartMaidEntity maid && maid.isOwnedBy(player)) {
                    maid.setDebugAnim(id);
                    source.sendSuccess(() -> Component.literal("女仆动画已设为 " + id
                            + (id == 0 ? "（清除）" : "：" + MaidAnimManager.getAnimNames()[id])), false);
                    return 1;
                }
            }
            source.sendFailure(Component.literal("未找到你的女仆"));
            return 0;
        } catch (Exception e) {
            SmartMaid.LOGGER.error("maidanim 执行失败", e);
            source.sendFailure(Component.literal("执行失败: " + e));
            return 0;
        }
    }
}
