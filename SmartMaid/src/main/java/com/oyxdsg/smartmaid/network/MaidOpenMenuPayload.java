package com.oyxdsg.smartmaid.network;

import com.oyxdsg.smartmaid.SmartMaid;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → 客户端：Shift+右键 打开<b>纯 Screen</b> 女仆主菜单（携带女仆实体 id，客户端据此取实体做预览/状态）。
 * N1 主菜单不挂容器，故用独立 S2C 包而非 openMenu。
 */
public record MaidOpenMenuPayload(int maidId, int food) implements CustomPacketPayload {
    public static final Type<MaidOpenMenuPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "open_maid_menu"));
    public static final StreamCodec<ByteBuf, MaidOpenMenuPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MaidOpenMenuPayload::maidId,
            ByteBufCodecs.VAR_INT, MaidOpenMenuPayload::food,
            MaidOpenMenuPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
