package com.oyxdsg.smartmaid.network;

import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.data.MaidSettings;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidAIBridge;
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidWsClient;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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

    /** 菜单动作包（C2S）：队列 op 白名单（Q9/N1） */
    public static final net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec<?, MaidMenuActionPayload> MAID_MENU_ACTION_CODEC =
            PayloadTypeRegistry.serverboundPlay().register(MaidMenuActionPayload.TYPE, MaidMenuActionPayload.STREAM_CODEC);

    /** 打开纯 Screen 主菜单（S2C，N1） */
    public static final net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec<?, MaidOpenMenuPayload> MAID_OPEN_MENU_CODEC =
            PayloadTypeRegistry.clientboundPlay().register(MaidOpenMenuPayload.TYPE, MaidOpenMenuPayload.STREAM_CODEC);

    /** 菜单状态快照（S2C，N1 步骤 2/3） */
    public static final net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec<?, MaidMenuStatePayload> MAID_MENU_STATE_CODEC =
            PayloadTypeRegistry.clientboundPlay().register(MaidMenuStatePayload.TYPE, MaidMenuStatePayload.STREAM_CODEC);

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
                sendMenuState(player, maid); // 回推新值（设置页实时刷新）
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

        // 菜单动作（N1）：转成 queue 指令走 MaidAIBridge，与 /maidai 同一条路径
        ServerPlayNetworking.registerGlobalReceiver(MaidMenuActionPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            player.level().getServer().execute(() -> {
                if (!(player.level() instanceof ServerLevel level)) {
                    return;
                }
                SmartMaidEntity maid = findOwnerMaid(level, player);
                if (maid == null) {
                    return;
                }
                String json;
                if ("enqueue".equals(payload.op()) && !payload.cmd().isEmpty()) {
                    // 菜单下发新任务：主人显式（P2）→ 插队首
                    JsonObject req = new JsonObject();
                    req.addProperty("id", "menu");
                    req.addProperty("cmd", payload.cmd());
                    req.add("params", parseParams(payload.params()));
                    req.addProperty("queue", true);
                    req.addProperty("priority", "owner");
                    json = req.toString();
                } else {
                    JsonObject params = new JsonObject();
                    params.addProperty("op", payload.op());
                    if ("stopGroup".equals(payload.op())) {
                        // 整组停止：把 id 字段当组 id 传入
                        params.addProperty("group", payload.id());
                    } else {
                        if (!payload.id().isEmpty()) {
                            params.addProperty("id", payload.id());
                        }
                        if (!payload.kind().isEmpty()) {
                            params.addProperty("kind", payload.kind());
                        }
                        if (payload.value() != 0) {
                            params.addProperty("to", payload.value());
                        }
                    }
                    JsonObject req = new JsonObject();
                    req.addProperty("id", "menu");
                    req.addProperty("cmd", "queue");
                    req.add("params", params);
                    json = req.toString();
                }
                com.oyxdsg.smartmaid.entity.ai.bridge.MaidCommandResult result = MaidAIBridge.execute(maid, json);
                // 动作后立刻回发状态 + 回执（N1 步骤 2/3）；周期查询不带回执，避免刷屏
                sendMenuState(player, maid, "query".equals(payload.op()) ? null : result);
            });
        });

        // 玩家断线时保存女仆数据（含任务队列）→ Q8 持久化不丢"退出前刚加的任务"
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player.level() instanceof ServerLevel level) {
                SmartMaidEntity maid = findOwnerMaid(level, player);
                if (maid != null) {
                    com.oyxdsg.smartmaid.data.MaidDataManager.save(maid);
                }
            }
        });
    }

    private static JsonObject parseParams(String s) {
        try {
            return com.google.gson.JsonParser.parseString(s).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    /** 向玩家发送一次菜单状态快照（S2C）：队列 + 战斗 + 饱食度。 */
    public static void sendMenuState(ServerPlayer player, SmartMaidEntity maid) {
        sendMenuState(player, maid, null);
    }

    /** 带回执的状态快照：回执附带 ok + 简短文案（供客户端 toast）。 */
    public static void sendMenuState(ServerPlayer player, SmartMaidEntity maid,
                                     com.oyxdsg.smartmaid.entity.ai.bridge.MaidCommandResult result) {
        JsonObject st = maid.getMaidTaskManager().snapshot();
        st.addProperty("food", maid.getMaidFood().getFoodLevel());
        st.add("settings", maid.getSettings().toJson());
        if (result != null) {
            st.addProperty("receipt_ok", result.ok());
            st.addProperty("receipt_msg", result.ok() ? result.step() : errOf(result));
        }
        ServerPlayNetworking.send(player, new MaidMenuStatePayload(st.toString()));
    }

    private static String errOf(com.oyxdsg.smartmaid.entity.ai.bridge.MaidCommandResult r) {
        if (r.result().has("error")) {
            return r.result().get("error").getAsString();
        }
        if (r.result().has("reason")) {
            return r.result().get("reason").getAsString();
        }
        return "失败";
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
