package com.oyxdsg.smartmaid;

import com.oyxdsg.smartmaid.command.SummonMaidCommand;
import com.oyxdsg.smartmaid.command.MaidAICommand;
import com.oyxdsg.smartmaid.command.MaidAnimCommand;
import com.oyxdsg.smartmaid.command.MaidPerceptionCommand;
import com.oyxdsg.smartmaid.command.MaidTaskCommand;
import com.oyxdsg.smartmaid.data.SmartMaidConfig;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidAutoTest;
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidWsClient;
import com.oyxdsg.smartmaid.entity.ai.craft.CraftExecutor;
import com.oyxdsg.smartmaid.init.ModEntities;
import com.oyxdsg.smartmaid.init.ModMenus;
import com.oyxdsg.smartmaid.network.ModNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.PermissionCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmartMaid implements ModInitializer {
    public static final String MOD_ID = "smartmaid";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // 错误接收通道：未捕获异常 + 主动上报 → <gameDir>/smartmaid/errors.jsonl（含服务端）
        com.oyxdsg.smartmaid.test.MaidErrorSink.setFallbackLogger(
                (msg, err) -> LOGGER.warn(msg, err));
        com.oyxdsg.smartmaid.test.MaidErrorSink.install("server");
        ModEntities.register();
        ModMenus.register();
        ModNetworking.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            // 权限策略（SmartMaidConfig.commandPermission）：
            //   auto（默认）—— 单人（集成服务器）用 LEVEL_ALL，整合包玩家不开作弊也能召唤；
            //                  专用服务器保持 LEVEL_GAMEMASTERS。
            // 玩家日常入口（召唤 / 任务）跟随该策略；调试与最强入口（/maidai 任意 JSON 指令、
            // /maidanim、/maidperception）保持管理员级 —— 桌宠下发指令走 WS，不经过命令权限。
            PermissionCheck common = SmartMaidConfig.commandPermission(selection);
            PermissionCheck admin = Commands.LEVEL_GAMEMASTERS;
            SummonMaidCommand.register(dispatcher, common);
            MaidTaskCommand.register(dispatcher, common);
            MaidAICommand.register(dispatcher, admin);
            MaidAnimCommand.register(dispatcher, admin);
            MaidPerceptionCommand.register(dispatcher, admin);
        });
        // 自动测试钩子：读 config/smartmaid/autotest.json 自动执行指令序列（无配置则静默）
        ServerTickEvents.END_SERVER_TICK.register(MaidAutoTest::onServerTick);
        // 配方缓存：服务器启动后清一次（避免整合包数千条配方每次合成都全表扫描）
        ServerLifecycleEvents.SERVER_STARTED.register(srv -> CraftExecutor.invalidateCache());
        // 桌宠联动（M5-b）：WebSocket Client 连桌宠 Server，上报感知 + 接收指令
        ServerLifecycleEvents.SERVER_STARTED.register(MaidWsClient::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(MaidWsClient::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(MaidWsClient::onServerTick);
        // 调试日志开关预热：读 config/smartmaid/main.json 的 debug / debugVerbose，
        // 并在启动日志里回显，方便确认开关状态（排查问题时不用猜日志为什么是空的）
        MaidDebug.reload();
        LOGGER.info("Smart Maid initialized (debug={}, verbose={})",
                MaidDebug.enabled(), MaidDebug.verbose());
    }
}
