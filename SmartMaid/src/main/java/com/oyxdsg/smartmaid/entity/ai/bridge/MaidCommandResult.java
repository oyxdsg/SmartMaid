package com.oyxdsg.smartmaid.entity.ai.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * AI 指令回执模型（M4）。
 *
 * <p>对齐 DESIGN_AI_INTERFACE 的 JSON 回执协议：</p>
 * <pre>{@code
 * {"id":"cmd-1","ok":true,"state":"running|done|failed|cancelled",
 *  "step":"当前步骤","result":{...}}
 * }</pre>
 *
 * <p>受理回执 {@code state}：任务已开始=running / 前置不满足或参数错误=failed /
 * 取消=cancelled。任务进行中的异步结果由感知事件队列（task_started/task_done）推送。</p>
 */
public record MaidCommandResult(String id, boolean ok, String state, String step, JsonObject result) {

    private static final Gson GSON = new Gson();

    public static MaidCommandResult ok(String id, String state, String step, JsonObject result) {
        return new MaidCommandResult(id, true, state, step, result == null ? new JsonObject() : result);
    }

    public static MaidCommandResult fail(String id, String reason) {
        JsonObject result = new JsonObject();
        result.addProperty("error", reason);
        return new MaidCommandResult(id, false, "failed", "rejected", result);
    }

    /** 序列化为 JSON 字符串（回执给 AI / WebSocket） */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", this.id);
        o.addProperty("ok", this.ok);
        o.addProperty("state", this.state);
        o.addProperty("step", this.step);
        o.add("result", this.result);
        return GSON.toJson(o);
    }
}
