package com.oyxdsg.smartmaid.command;

import com.mojang.brigadier.CommandDispatcher;
import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.data.MaidDataManager;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidWsClient;
import com.oyxdsg.smartmaid.init.ModEntities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;

public final class SummonMaidCommand {

    private SummonMaidCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("summonmaid")
                // P4 发布收尾：仅管理员（权限等级 2）可召唤，防止在他人服务器乱刷女仆
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ctx -> summon(ctx.getSource(), ctx.getSource().getPlayerOrException())));
    }

    private static int summon(CommandSourceStack source, ServerPlayer player) {
        try {
            return doSummon(source, player);
        } catch (Exception e) {
            SmartMaid.LOGGER.error("summonmaid 执行失败", e);
            source.sendFailure(Component.literal("女仆召唤失败: " + e));
            return 0;
        }
    }

    private static int doSummon(CommandSourceStack source, ServerPlayer player) {
        ServerLevel level = player.level();

        // 每玩家仅限一只
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof SmartMaidEntity maid
                    && player.getUUID().equals(maid.getOwnerReference().getUUID())) {
                source.sendSuccess(() -> Component.literal("该玩家已有一只女仆，不能重复召唤"), false);
                return 0;
            }
        }

        SmartMaidEntity maid = ModEntities.SMART_MAID.create(level, EntitySpawnReason.COMMAND);
        if (maid == null) {
            return 0;
        }
        maid.setPos(player.getX(), player.getY(), player.getZ());
        maid.tame(player);
        maid.setOrderedToSit(false);
        level.addFreshEntity(maid);
        // 从本地文件恢复上次的装备/物品栏数据
        MaidDataManager.load(maid);
        // 加载该玩家的女仆设置并即时应用（最大生命/饱食消耗/回血速度等）
        maid.getSettings();
        // 告知桌宠女仆已上线（桌宠按自己的设置决定是否隐退窗口）
        MaidWsClient.notifyPresence(maid, true);
        // 召唤欢迎气泡
        maid.showBubble(Component.literal("主人好呀，我来了~"), 80);
        source.sendSuccess(() -> Component.literal(
                "女仆已召唤。聊天栏输入 /maidchat 进入对话模式（再输一次退出），或按住 Y 语音对话。"), true);
        return 1;
    }
}
