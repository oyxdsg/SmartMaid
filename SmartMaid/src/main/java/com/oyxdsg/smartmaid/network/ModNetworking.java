package com.oyxdsg.smartmaid.network;

import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.data.MaidSettings;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidWsClient;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * 网络注册：女仆 GUI 动作包（C2S）+ 服务端接收处理。
 * 服务端按发送玩家查找其唯一的女仆执行动作。
 */
public final class ModNetworking {
    private ModNetworking() {
    }

    /** 保留寄存器返回值（Fabric 要求 static final 持有），记录已注册的包类型 */
    public static final net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec<?, MaidCommandPayload> MAID_COMMAND_CODEC =
            PayloadTypeRegistry.serverboundPlay().register(MaidCommandPayload.TYPE, MaidCommandPayload.STREAM_CODEC);

    /** 女仆设置修改包（C2S） */
    public static final net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec<?, MaidSettingsPayload> MAID_SETTINGS_CODEC =
            PayloadTypeRegistry.serverboundPlay().register(MaidSettingsPayload.TYPE, MaidSettingsPayload.STREAM_CODEC);

    /** 玩家聊天栏对话包（C2S） */
    public static final net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec<?, MaidChatPayload> MAID_CHAT_CODEC =
            PayloadTypeRegistry.serverboundPlay().register(MaidChatPayload.TYPE, MaidChatPayload.STREAM_CODEC);

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(MaidCommandPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            player.level().getServer().execute(() -> {
                if (!(player.level() instanceof ServerLevel level)) {
                    return;
                }
                SmartMaidEntity maid = findOwnerMaid(level, player);
                if (maid == null) {
                    SmartMaid.LOGGER.debug("GUI 动作未找到玩家 {} 的女仆", player.getName().getString());
                    return;
                }
                switch (payload.action()) {
                    case MaidCommandPayload.ACTION_TOGGLE_SIT -> {
                        // 坐/站统一切换：落地吸附 + 移动锁定都在 setOrderedToSit 内处理
                        maid.setOrderedToSit(!maid.isOrderedToSit());
                    }
                    case MaidCommandPayload.ACTION_RECALL -> maid.teleportToOwner();
                    case MaidCommandPayload.ACTION_CLEAR_TARGET -> maid.setTarget(null);
                    case MaidCommandPayload.ACTION_OPEN_INVENTORY -> player.openMenu(new com.oyxdsg.smartmaid.gui.MaidInventoryMenuProvider(maid));
                    default -> SmartMaid.LOGGER.warn("未知女仆动作: {}", payload.action());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(MaidSettingsPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            player.level().getServer().execute(() -> {
                if (!(player.level() instanceof ServerLevel level)) {
                    return;
                }
                SmartMaidEntity maid = findOwnerMaid(level, player);
                if (maid == null) {
                    return;
                }
                // 服务端权威：只接受字段名 + 档位索引，写入后落盘并即时应用到女仆
                maid.getSettings().applyField(payload.field(), payload.value());
                MaidSettings.save(player.getUUID());
                maid.getSettings().applyToMaid(maid);
                MaidDebug.log("设置更新 " + payload.field() + "=" + payload.value());
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(MaidChatPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            player.level().getServer().execute(() -> {
                if (!(player.level() instanceof ServerLevel level)) {
                    return;
                }
                SmartMaidEntity maid = findOwnerMaid(level, player);
                if (maid == null) {
                    player.sendSystemMessage(Component.literal("你还没有召唤女仆"));
                    return;
                }
                // 26.2 客户端聊天组件为 private，这里用系统消息回显玩家发言
                player.sendSystemMessage(Component.literal("你：" + payload.text()));
                MaidWsClient.sendChat(maid, payload.text());
            });
        });
    }

    /** 查找某玩家名下的女仆（每玩家仅一只） */
    private static SmartMaidEntity findOwnerMaid(ServerLevel level, ServerPlayer player) {
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof SmartMaidEntity maid && maid.isOwnedBy(player)) {
                return maid;
            }
        }
        return null;
    }
}
