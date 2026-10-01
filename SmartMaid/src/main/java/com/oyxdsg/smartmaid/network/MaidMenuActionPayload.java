package com.oyxdsg.smartmaid.network;

import com.oyxdsg.smartmaid.SmartMaid;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 女仆菜单动作包（客户端 → 服务端，N1/Q9 协议）。
 *
 * <p>两种用法：</p>
 * <ul>
 *   <li><b>队列操作</b>：{@code op} = query/cancelCurrent/promote/stop/moveUp/move/clear/resume/
 *       pauseCurrent/setLongTerm/stopGroup，转成 {@code {"cmd":"queue","params":{...}}}；</li>
 *   <li><b>入队新任务</b>：{@code op="enqueue"}，携带 {@code cmd} + {@code paramsJson}，
 *       转成 {@code {"cmd":<cmd>,"params":<json>,"queue":true,"priority":"owner"}}（菜单=主人 P2）。</li>
 * </ul>
 */
public record MaidMenuActionPayload(String op, String id, String kind, int value, String cmd, String params)
        implements CustomPacketPayload {

    public static final Type<MaidMenuActionPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SmartMaid.MOD_ID, "maid_menu_action"));
    public static final StreamCodec<ByteBuf, MaidMenuActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MaidMenuActionPayload::op,
            ByteBufCodecs.STRING_UTF8, MaidMenuActionPayload::id,
            ByteBufCodecs.STRING_UTF8, MaidMenuActionPayload::kind,
            ByteBufCodecs.VAR_INT, MaidMenuActionPayload::value,
            ByteBufCodecs.STRING_UTF8, MaidMenuActionPayload::cmd,
            ByteBufCodecs.STRING_UTF8, MaidMenuActionPayload::params,
            MaidMenuActionPayload::new);

    public static MaidMenuActionPayload of(String op) {
        return new MaidMenuActionPayload(op, "", "", 0, "", "");
    }

    public static MaidMenuActionPayload op(String op, String id, String kind) {
        return new MaidMenuActionPayload(op, id, kind, 0, "", "");
    }

    public static MaidMenuActionPayload enqueue(String cmd, String paramsJson) {
        return new MaidMenuActionPayload("enqueue", "", "", 0, cmd, paramsJson);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
