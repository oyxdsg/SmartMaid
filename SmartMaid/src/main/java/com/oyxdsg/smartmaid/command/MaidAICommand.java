package com.oyxdsg.smartmaid.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidAIBridge;
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidCommandResult;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * AI 指令调试入口：/maidai &lt;json&gt; 把一条 JSON 指令交给 {@link MaidAIBridge} 执行并回显回执。
 *
 * <p>与桌宠 WebSocket（M5）走同一桥接层，用于开发期直接验证 JSON 指令协议。</p>
 *
 * <p>示例：</p>
 * <ul>
 *     <li>{@code /maidai {"id":"a1","cmd":"guard","params":{"range":10}}}</li>
 *     <li>{@code /maidai {"id":"a2","cmd":"move","params":{"pos":[100,-60,-50]}}}</li>
 *     <li>{@code /maidai {"id":"a3","cmd":"mine","params":{"pos":[100,-60,-50],"range":4,"count":8},"persist":true}}</li>
 *     <li>{@code /maidai {"id":"a4","cmd":"status"}}</li>
 * </ul>
 */
public final class MaidAICommand {

    private MaidAICommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("maidai")
                // P4 发布收尾：AI 指令桥接需权限等级 2
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("json", StringArgumentType.greedyString())
                        .executes(ctx -> exec(ctx.getSource(),
                                StringArgumentType.getString(ctx, "json")))));
    }

    private static int exec(CommandSourceStack source, String json) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            if (!(player.level() instanceof ServerLevel level)) {
                return 0;
            }
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof SmartMaidEntity maid && maid.isOwnedBy(player)) {
                    MaidCommandResult result = MaidAIBridge.execute(maid, json);
                    MaidDebug.log("maidai 指令: " + json);
                    MaidDebug.log("maidai 回执: " + result.toJson());
                    source.sendSuccess(() -> Component.literal(result.toJson()), false);
                    return 1;
                }
            }
            source.sendFailure(Component.literal("未找到你的女仆"));
            return 0;
        } catch (Exception e) {
            SmartMaid.LOGGER.error("maidai 执行失败", e);
            source.sendFailure(Component.literal("执行失败: " + e));
            return 0;
        }
    }
}
