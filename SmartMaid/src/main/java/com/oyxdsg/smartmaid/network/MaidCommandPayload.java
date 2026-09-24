package com.oyxdsg.smartmaid.network;

import com.oyxdsg.smartmaid.SmartMaid;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 女仆 GUI 动作包（客户端 → 服务端）。
 * 只携带动作类型；服务端按发送玩家找到其唯一的女仆并执行动作。
 */
public record MaidCommandPayload(int action) implements CustomPacketPayload {
    public static final Type<MaidCommandPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "maid_command"));
    public static final StreamCodec<ByteBuf, MaidCommandPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MaidCommandPayload::action,
            MaidCommandPayload::new);

    /** 切换坐下/站起 */
    public static final int ACTION_TOGGLE_SIT = 0;
    /** 召回：传送到主人身边 */
    public static final int ACTION_RECALL = 1;
    /** 清空攻击目标 */
    public static final int ACTION_CLEAR_TARGET = 2;
    /** 打开女仆背包 */
    public static final int ACTION_OPEN_INVENTORY = 3;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
