package com.oyxdsg.smartmaid.network;

import com.oyxdsg.smartmaid.SmartMaid;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 女仆设置修改包（客户端 → 服务端）。
 *
 * <p>只传「字段名 + 档位索引」：服务端权威校验并写入 {@code MaidSettings}，
 * 再通过菜单的 ContainerData 把新值同步回客户端显示。</p>
 */
public record MaidSettingsPayload(String field, int value) implements CustomPacketPayload {
    public static final Type<MaidSettingsPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "maid_settings"));
    public static final StreamCodec<ByteBuf, MaidSettingsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MaidSettingsPayload::field,
            ByteBufCodecs.VAR_INT, MaidSettingsPayload::value,
            MaidSettingsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
