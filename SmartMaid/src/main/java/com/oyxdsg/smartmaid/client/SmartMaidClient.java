package com.oyxdsg.smartmaid.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.client.chat.MaidChatClient;
import com.oyxdsg.smartmaid.client.gui.MaidControlScreen;
import com.oyxdsg.smartmaid.client.gui.MaidInventoryScreen;
import com.oyxdsg.smartmaid.client.renderer.SmartMaidRenderer;
import com.oyxdsg.smartmaid.init.ModEntities;
import com.oyxdsg.smartmaid.init.ModMenus;
import com.oyxdsg.smartmaid.network.MaidChatPayload;
import com.oyxdsg.smartmaid.network.MaidCommandPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class SmartMaidClient implements ClientModInitializer {
    /** Shift+E：打开女仆背包（KeyMapping 构造即自动注册，Shift 在按键处理中手动判定） */
    public static final KeyMapping OPEN_INVENTORY = new KeyMapping(
            "key.smartmaid.open_inventory", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_E, KeyMapping.Category.INVENTORY);

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModEntities.SMART_MAID, SmartMaidRenderer::new);
        MenuScreens.register(ModMenus.MAID_INVENTORY_MENU, MaidInventoryScreen::new);
        MenuScreens.register(ModMenus.MAID_CONTROL_MENU, MaidControlScreen::new);

        // 女仆对话模式：拦截普通聊天文本，转给女仆 AI；//maidchat 开关
        ClientSendMessageEvents.ALLOW_CHAT.register(text -> {
            if (!MaidChatClient.isChatMode() || text.isBlank()) {
                return true;
            }
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                ClientPlayNetworking.send(new MaidChatPayload(text));
            }
            return false;
        });

        // /maidchat：在聊天栏输入，开关女仆对话模式（客户端本地命令，不打扰服务器）
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("maidchat").executes(ctx -> {
                    boolean on = MaidChatClient.toggle();
                    ctx.getSource().sendFeedback(Component.literal(on
                            ? "已进入女仆对话模式：直接在聊天栏说话即可（输入 /maidchat 退出）"
                            : "已退出女仆对话模式"));
                    return 1;
                })));

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            while (OPEN_INVENTORY.consumeClick()) {
                boolean shift = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                        || InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
                if (shift) {
                    // 吞掉原版 E 背包键，避免同时打开玩家背包
                    client.options.keyInventory.consumeClick();
                    if (client.player != null) {
                        ClientPlayNetworking.send(new MaidCommandPayload(MaidCommandPayload.ACTION_OPEN_INVENTORY));
                    }
                }
            }
        });

        SmartMaid.LOGGER.info("SmartMaid 客户端初始化完成");
    }
}
