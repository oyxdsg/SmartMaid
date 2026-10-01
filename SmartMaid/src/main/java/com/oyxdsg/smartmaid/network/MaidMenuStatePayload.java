package com.oyxdsg.smartmaid.network;

import com.oyxdsg.smartmaid.SmartMaid;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → 客户端：女仆菜单状态快照（S2C，N1 步骤 2/3）——
 * 当前任务、长期/短期队列、战斗、饱食度，序列化为 JSON 字符串随包发送。
 */
public record MaidMenuStatePayload(String json) implements CustomPacketPayload {
    public static final Type<MaidMenuStatePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "maid_menu_state"));
    public static final StreamCodec<ByteBuf, MaidMenuStatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MaidMenuStatePayload::json,
            MaidMenuStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
