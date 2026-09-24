package com.oyxdsg.smartmaid;

import com.oyxdsg.smartmaid.command.SummonMaidCommand;
import com.oyxdsg.smartmaid.command.MaidAICommand;
import com.oyxdsg.smartmaid.command.MaidAnimCommand;
import com.oyxdsg.smartmaid.command.MaidPerceptionCommand;
import com.oyxdsg.smartmaid.command.MaidTaskCommand;
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidAutoTest;
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidWsClient;
import com.oyxdsg.smartmaid.init.ModEntities;
import com.oyxdsg.smartmaid.init.ModMenus;
import com.oyxdsg.smartmaid.network.ModNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmartMaid implements ModInitializer {
    public static final String MOD_ID = "smartmaid";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModEntities.register();
        ModMenus.register();
        ModNetworking.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            SummonMaidCommand.register(dispatcher);
            MaidAnimCommand.register(dispatcher);
            MaidTaskCommand.register(dispatcher);
            MaidPerceptionCommand.register(dispatcher);
            MaidAICommand.register(dispatcher);
        });
        // 自动测试钩子：读 config/smartmaid/autotest.json 自动执行指令序列（无配置则静默）
        ServerTickEvents.END_SERVER_TICK.register(MaidAutoTest::onServerTick);
        // 桌宠联动（M5-b）：WebSocket Client 连桌宠 Server，上报感知 + 接收指令
        ServerLifecycleEvents.SERVER_STARTED.register(MaidWsClient::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(MaidWsClient::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(MaidWsClient::onServerTick);
        LOGGER.info("Smart Maid initialized");
    }
}
