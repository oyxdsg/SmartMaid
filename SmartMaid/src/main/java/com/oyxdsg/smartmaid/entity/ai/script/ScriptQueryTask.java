package com.oyxdsg.smartmaid.entity.ai.script;

import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidAITask;

/**
 * 查询原子任务包装（Atomic Command Protocol §3.2）：查询在 start 时同步执行完毕，
 * 立即完成，把结构化 result 交给脚本引擎（变量绑定 / 条件判断用）。
 */
public class ScriptQueryTask extends MaidAITask {

    private final JsonObject result;

    public ScriptQueryTask(String cmd, JsonObject result) {
        super(cmd);
        this.result = result;
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return true;
    }

    @Override
    public void start(SmartMaidEntity maid) {
    }

    @Override
    public void tick(SmartMaidEntity maid) {
    }

    @Override
    public boolean isDone() {
        return true;
    }

    @Override
    public String result() {
        return this.result == null ? "" : this.result.toString();
    }

    @Override
    public JsonObject resultJson() {
        return this.result;
    }
}
