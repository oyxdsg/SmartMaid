package com.oyxdsg.smartmaid.command;

import com.mojang.brigadier.CommandDispatcher;
import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.perception.MaidPerception;
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionModule;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * 开发调试指令：/maidperception 强制刷新一次感知快照并输出到日志，
 * 聊天栏回显摘要。用于校验感知模块是否正确采集数据。
 */
public final class MaidPerceptionCommand {

    private MaidPerceptionCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("maidperception")
                // P4 发布收尾：感知快照调试指令需权限等级 2
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ctx -> dump(ctx.getSource())));
    }

    private static int dump(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            if (!(player.level() instanceof ServerLevel level)) {
                return 0;
            }
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof SmartMaidEntity maid && maid.isOwnedBy(player)) {
                    PerceptionModule module = maid.getPerceptionModule();
                    module.tick(); // 强制立即采样一次
                    MaidPerception p = module.current();
                    if (p == null) {
                        source.sendFailure(Component.literal("感知快照尚未生成"));
                        return 0;
                    }
                    String json = p.toJson();
                    MaidDebug.log("手动感知快照: " + json);
                    com.google.gson.JsonObject self = p.self();
                    com.google.gson.JsonObject status = self.getAsJsonObject("status");
                    String task = status.has("task_id") && !status.get("task_id").isJsonNull()
                            ? status.get("task_id").getAsString() : "无";
                    source.sendSuccess(() -> Component.literal(
                            "感知快照已输出到日志（大小 " + json.length() + "B）: "
                                    + "pos=" + self.get("pos")
                                    + " health=" + self.get("health")
                                    + " task=" + task
                                    + " 事件数=" + p.events().size()),
                            false);
                    return 1;
                }
            }
            source.sendFailure(Component.literal("未找到你的女仆"));
            return 0;
        } catch (Exception e) {
            SmartMaid.LOGGER.error("maidperception 执行失败", e);
            source.sendFailure(Component.literal("执行失败: " + e));
            return 0;
        }
    }
}
