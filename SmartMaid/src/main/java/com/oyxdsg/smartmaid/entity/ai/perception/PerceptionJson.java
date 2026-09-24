package com.oyxdsg.smartmaid.entity.ai.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 感知快照 JSON 序列化入口（M5 WebSocket 桌宠联动对接用）。
 *
 * <p>{@link #snapshot} 对应 WebSocket 协议中的 {@code perception} 消息（状态快照），
 * {@link #events} 对应 {@code event} 消息（即时事件）。</p>
 */
public final class PerceptionJson {

    private PerceptionJson() {
    }

    /** 快照 → JSON 字符串（perception 消息） */
    public static String snapshot(MaidPerception perception) {
        return perception.toJson();
    }

    /** 事件列表 → JSON 数组字符串（event 消息） */
    public static String events(List<JsonObject> events) {
        JsonArray arr = new JsonArray();
        events.forEach(arr::add);
        return arr.toString();
    }
}
