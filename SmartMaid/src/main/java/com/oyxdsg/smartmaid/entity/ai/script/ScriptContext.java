package com.oyxdsg.smartmaid.entity.ai.script;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本执行上下文（Atomic Command Protocol §3）：变量表 + 每步结果日志 + 终止信号 + 执行步计数。
 */
public class ScriptContext {

    /** 变量表：`as` 绑定的查询/子任务结构化结果、vars 初始值、assign 赋值 */
    public final Map<String, JsonElement> vars = new LinkedHashMap<>();

    /** 已执行步骤的扁平日志（含 if/loop 展开的分支），供最终回执 steps */
    public final List<JsonObject> stepLog = new ArrayList<>();

    /** 上一步的 result（$last 引用） */
    public JsonElement lastResult;

    /** terminate 信号 */
    public String terminateReason;
    public JsonObject terminateResult;
    public boolean terminated;

    /** 实际执行步数（含分支/循环展开），超 maxSteps 强制终止 */
    public int executedSteps;

    /** 脚本名（回执/日志用） */
    public String scriptName = "script";

    /** 记录一条已执行步骤 */
    public void logStep(String cmd, boolean ok, JsonElement result) {
        JsonObject rec = new JsonObject();
        rec.addProperty("cmd", cmd);
        rec.addProperty("ok", ok);
        if (result != null) {
            rec.add("result", result);
        }
        this.stepLog.add(rec);
        this.lastResult = result;
    }
}
