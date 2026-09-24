package com.oyxdsg.smartmaid.network;

import com.oyxdsg.smartmaid.SmartMaid;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 玩家在聊天栏对女仆说的话（客户端 → 服务端）。
 *
 * <p>客户端处于「女仆对话模式」时，普通聊天文本被拦截后走本包；服务端把它回显给玩家
 * 并沿 WebSocket 上行 {@code chat} 给桌宠 AI。</p>
 */
public record MaidChatPayload(String text) implements CustomPacketPayload {
    public static final Type<MaidChatPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "maid_chat"));
    public static final StreamCodec<ByteBuf, MaidChatPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MaidChatPayload::text,
            MaidChatPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
