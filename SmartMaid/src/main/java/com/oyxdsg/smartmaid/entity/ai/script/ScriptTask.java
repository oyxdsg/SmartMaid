package com.oyxdsg.smartmaid.entity.ai.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.bridge.MaidAIBridge;
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidAITask;
import com.oyxdsg.smartmaid.entity.ai.structure.HarvestTask;
import com.oyxdsg.smartmaid.entity.ai.structure.OreStructure;
import com.oyxdsg.smartmaid.entity.ai.structure.ProduceFilter;
import com.oyxdsg.smartmaid.entity.ai.structure.StructureRegistry;
import com.oyxdsg.smartmaid.entity.ai.structure.StructureScan;
import com.oyxdsg.smartmaid.entity.ai.structure.StructureType;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 脚本任务（Atomic Command Protocol §3）：AI 一次性规划的指令流执行器。
 *
 * <p>控制原语：{@code as}（变量绑定，挂在任意指令上）/ {@code $var.path}（变量引用）/
 * {@code if}（条件分支）/ {@code loop}（条件循环 + max_iter）/ {@code assign}（整数算术）/
 * {@code terminate}（提前终止 + 回执）。子任务复用 {@link MaidAIBridge#buildTask}，
 * 查询原子与 harvest 在本引擎内特殊构造。</p>
 *
 * <p>护栏：max_steps（实际执行步，含分支/循环展开）、每步由 MaidTaskManager 的 60s
 * 超时兜底、禁止嵌套 script、战斗/坐下/取消由 MaidTaskManager 统一打断。</p>
 */
public class ScriptTask extends MaidAITask {

    private static final int FAIL_ABORT = 0;
    private static final int FAIL_CONTINUE = 1;

    /** 整个脚本最大运行 tick（10 分钟，含移动/收割等耗时段）。 */
    private static final int MAX_SCRIPT_TICKS = 20 * 60 * 10;
    /** 单个子任务（一步）最大运行 tick（60 秒，防止 move/attack 等卡死）。 */
    private static final int MAX_STEP_TICKS = 20 * 60;

    private final String reqId;
    private final ScriptContext ctx = new ScriptContext();
    private final List<JsonObject> steps;
    private final int failPolicy;
    private final int maxSteps;
    private final boolean reportEach;

    private final Deque<Frame> stack = new ArrayDeque<>();
    private MaidAITask current;
    private String currentCmd;
    private String currentAs;
    private String lastSubError;
    private boolean done;
    private boolean cancelled;
    private JsonObject failInfo;
    private int totalTicks;
    private int stepStartTick;

    public ScriptTask(String reqId, JsonObject script) {
        super("script");
        this.reqId = reqId;
        this.failPolicy = script.has("fail") && "continue".equals(script.get("fail").getAsString())
                ? FAIL_CONTINUE : FAIL_ABORT;
        this.maxSteps = script.has("max_steps") ? Math.max(1, script.get("max_steps").getAsInt()) : 64;
        this.reportEach = script.has("report_each") && script.get("report_each").getAsBoolean();
        this.ctx.scriptName = script.has("name") ? script.get("name").getAsString() : "script";
        if (script.has("vars") && script.get("vars").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : script.getAsJsonObject("vars").entrySet()) {
                this.ctx.vars.put(e.getKey(), e.getValue().deepCopy());
            }
        }
        if (!this.ctx.vars.containsKey("target_count")) {
            this.ctx.vars.put("target_count", new JsonPrimitive(0));
        }
        JsonArray arr = script.has("steps") && script.get("steps").isJsonArray()
                ? script.getAsJsonArray("steps") : new JsonArray();
        this.steps = jsonArrayToList(arr);
    }

    @Override
    public boolean canStart(SmartMaidEntity maid) {
        return !this.steps.isEmpty();
    }

    @Override
    public void start(SmartMaidEntity maid) {
        // 引擎自动变量：$maid.pos（脚本开始时女仆坐标，"回来"锚点）
        BlockPos p = maid.blockPosition();
        JsonObject maidVar = new JsonObject();
        maidVar.add("pos", StructureScan.posArray(p));
        this.ctx.vars.put("maid", maidVar);

        this.stack.clear();
        this.stack.push(new Frame(this.steps));
        MaidDebug.log("Script start: " + this.ctx.scriptName + " steps=" + this.steps.size());
    }

    @Override
    public void tick(SmartMaidEntity maid) {
        if (this.done || this.cancelled) {
            return;
        }
        // 整个脚本总超时护栏（10 分钟；MaidTaskManager 不对 continuous 任务设 60s 上限）
        this.totalTicks++;
        if (this.totalTicks > MAX_SCRIPT_TICKS) {
            JsonObject rec = new JsonObject();
            rec.addProperty("cmd", "#script_timeout");
            rec.addProperty("ok", false);
            rec.addProperty("error", "脚本执行超时");
            this.failInfo = rec;
            this.terminate("脚本执行超时", rec);
            this.done = true;
            return;
        }
        if (this.current != null) {
            this.current.tick(maid);
            // 单步子任务超时（60s）：move/attack 等卡死时强制结束该步
            if (!this.current.isDone() && maid.tickCount - this.stepStartTick > MAX_STEP_TICKS) {
                MaidDebug.log("Script 步超时: " + this.currentCmd + " → 强制结束");
                this.current.forceStop(maid);
                JsonObject rec = new JsonObject();
                rec.addProperty("cmd", this.currentCmd);
                rec.addProperty("ok", false);
                rec.addProperty("error", "步骤执行超时");
                this.ctx.logStep(this.currentCmd, false, rec);
                if (this.failPolicy == FAIL_ABORT) {
                    this.failInfo = rec;
                    this.terminate("步骤超时: " + this.currentCmd, rec);
                    this.done = true;
                    return;
                }
                this.current = null;
                return;
            }
            if (this.current.isDone()) {
                this.recordStep();
                this.current = null;
            }
            return;
        }
        this.advance(maid);
    }

    /** 从执行栈取下一步并推进（含控制原语就地处理 / 子任务构造） */
    private void advance(SmartMaidEntity maid) {
        while (!this.done && !this.cancelled) {
            if (this.ctx.terminated) {
                this.done = true;
                return;
            }
            if (this.stack.isEmpty()) {
                this.done = true;
                return;
            }
            Frame f = this.stack.peek();
            if (f.isLoop) {
                if (f.iterLeft <= 0 || (f.whileCond != null && !ScriptRef.evalCond(f.whileCond, this.ctx))) {
                    this.stack.pop();
                    continue;
                }
                f.iterLeft--;
                this.stack.push(new Frame(f.body));
                continue;
            }
            if (f.index >= f.steps.size()) {
                this.stack.pop();
                continue;
            }
            JsonObject step = f.steps.get(f.index++);
            this.ctx.executedSteps++;
            if (this.ctx.executedSteps > this.maxSteps) {
                JsonObject rec = new JsonObject();
                rec.addProperty("cmd", "#max_steps");
                rec.addProperty("ok", false);
                rec.addProperty("error", "超过步数上限 " + this.maxSteps);
                this.failInfo = rec;
                this.terminate("超过步数上限 " + this.maxSteps, rec);
                this.done = true;
                return;
            }
            if (!this.runStep(maid, step)) {
                return; // current 已设置，等待子任务
            }
        }
    }

    /** 执行单个 step；返回 true = 立即完成（继续），false = 已挂起子任务 */
    private boolean runStep(SmartMaidEntity maid, JsonObject step) {
        // assign：整数算术赋值
        if (step.has("assign")) {
            String var = step.get("assign").getAsString();
            String expr = step.has("expr") ? step.get("expr").getAsString() : "";
            Integer v = ScriptRef.evalInt(expr, this.ctx);
            if (v != null) {
                this.ctx.vars.put(var, new JsonPrimitive(v));
            }
            this.ctx.logStep("assign:" + var, v != null, v == null ? null : new JsonPrimitive(v));
            return true;
        }
        // if：条件分支，压入 then/else 帧
        if (step.has("if")) {
            boolean ok = ScriptRef.evalCond(step.getAsJsonObject("if"), this.ctx);
            JsonArray body = ok
                    ? (step.has("then") ? step.getAsJsonArray("then") : null)
                    : (step.has("else") ? step.getAsJsonArray("else") : null);
            if (body != null && body.size() > 0) {
                this.stack.push(new Frame(jsonArrayToList(body)));
            }
            this.ctx.logStep("if", ok, null);
            return true;
        }
        // loop：压入循环帧
        if (step.has("loop")) {
            JsonObject l = step.getAsJsonObject("loop");
            JsonArray body = l.has("body") && l.get("body").isJsonArray() ? l.getAsJsonArray("body") : new JsonArray();
            int maxIter = l.has("max_iter") ? Math.max(1, l.get("max_iter").getAsInt()) : 20;
            JsonObject whileCond = l.has("while") && l.get("while").isJsonObject()
                    ? l.getAsJsonObject("while") : null;
            this.stack.push(new Frame(whileCond, jsonArrayToList(body), maxIter));
            this.ctx.logStep("loop", true, null);
            return true;
        }
        // terminate：提前终止 + 回执（result 支持 $var 引用解析）
        if (step.has("terminate")) {
            JsonObject t = step.getAsJsonObject("terminate");
            JsonObject result = t.has("result") && t.get("result").isJsonObject()
                    ? ScriptRef.resolve(t.getAsJsonObject("result"), this.ctx) : null;
            this.terminate(t.has("reason") ? t.get("reason").getAsString() : "terminated",
                    result);
            return true;
        }
        // 指令 step
        String cmd = step.has("cmd") ? step.get("cmd").getAsString() : "";
        JsonObject params = ScriptRef.resolve(
                step.has("params") ? step.getAsJsonObject("params") : new JsonObject(), this.ctx);
        this.currentCmd = cmd;
        this.currentAs = step.has("as") ? step.get("as").getAsString() : null;
        MaidAITask task = buildSubTask(maid, cmd, params);
        if (task == null) {
            MaidDebug.log("Script 步失败: " + cmd + "（" + this.lastSubError + "） fail="
                    + (this.failPolicy == FAIL_ABORT ? "abort" : "continue"));
            JsonObject rec = new JsonObject();
            rec.addProperty("cmd", cmd);
            rec.addProperty("ok", false);
            if (this.lastSubError != null) {
                rec.addProperty("error", this.lastSubError);
            }
            this.ctx.logStep(cmd, false, rec);
            if (this.failPolicy == FAIL_ABORT) {
                this.failInfo = rec;
                this.terminate("步骤失败: " + cmd + "（" + this.lastSubError + "）", rec);
            }
            return true;
        }
        if (!task.canStart(maid)) {
            MaidDebug.log("Script 步前置不满足: " + cmd);
            JsonObject rec = new JsonObject();
            rec.addProperty("cmd", cmd);
            rec.addProperty("ok", false);
            rec.addProperty("error", "前置条件不满足");
            this.ctx.logStep(cmd, false, rec);
            if (this.failPolicy == FAIL_ABORT) {
                this.failInfo = rec;
                this.terminate("步骤前置不满足: " + cmd, rec);
            }
            return true;
        }
        this.current = task;
        this.stepStartTick = maid.tickCount;
        task.start(maid);
        MaidDebug.log("Script 步开始: " + cmd + " → " + task.taskId());
        return false;
    }

    /** 子任务构造：查询原子 / harvest / 禁止嵌套 script / 其余复用 MaidAIBridge */
    private MaidAITask buildSubTask(SmartMaidEntity maid, String cmd, JsonObject params) {
        this.lastSubError = null;
        if (ScriptQueries.isQuery(cmd)) {
            StringBuilder err = new StringBuilder();
            JsonObject r = ScriptQueries.query(maid, cmd, params, err);
            if (r == null) {
                this.lastSubError = err.length() > 0 ? err.toString() : "查询失败";
                return null;
            }
            return new ScriptQueryTask(cmd, r);
        }
        if (cmd.equals("harvest")) {
            StringBuilder err = new StringBuilder();
            BlockPos pos = MaidAIBridge.parsePos(maid, params, "pos", err);
            if (pos == null) {
                this.lastSubError = err.length() > 0 ? err.toString() : "harvest 缺少 pos";
                return null;
            }
            String produce = params.has("produce") ? params.get("produce").getAsString() : null;
            java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> matcher =
                    ProduceFilter.compile(produce).matcher();
            int count = 0;
            if (params.has("count")) {
                JsonElement ce = params.get("count");
                if (ce.isJsonPrimitive() && ce.getAsJsonPrimitive().isNumber()) {
                    count = ce.getAsInt();
                } else {
                    this.lastSubError = "harvest 的 count 参数不是数字";
                    return null;
                }
            }
            // 结构类型：显式 structure 参数 > 从 produce 推断（矿石 tag → ore）> 缺省 tree
            String structId = params.has("structure") ? params.get("structure").getAsString() : null;
            StructureType st = structId != null
                    ? StructureRegistry.get(structId)
                    : (OreStructure.isOreProduce(produce)
                        ? StructureRegistry.get("ore") : StructureRegistry.get("tree"));
            if (st == null) {
                this.lastSubError = "未知结构类型: " + structId;
                return null;
            }
            return new HarvestTask(pos, matcher, count, st);
        }
        if (cmd.equals("script")) {
            this.lastSubError = "脚本不允许嵌套 script";
            return null;
        }
        StringBuilder err = new StringBuilder();
        MaidAITask task = MaidAIBridge.buildTask(maid, cmd, params, err);
        if (task == null) {
            this.lastSubError = err.length() > 0 ? err.toString() : "未知指令: " + cmd;
        }
        return task;
    }

    /** 子任务完成：记录步骤（含结构化 result / as 绑定） */
    private void recordStep() {
        JsonObject r = this.current.resultJson();
        JsonElement logResult = r != null ? r : new JsonPrimitive(this.current.result());
        this.ctx.logStep(this.currentCmd, true, logResult);
        if (this.currentAs != null && r != null) {
            this.ctx.vars.put(this.currentAs, r);
        }
        if (MaidDebug.verbose()) {
            MaidDebug.log("Script 步完成: " + this.currentCmd + " " + logResult);
        }
    }

    private void terminate(String reason, JsonObject result) {
        this.ctx.terminated = true;
        this.ctx.terminateReason = reason;
        this.ctx.terminateResult = result;
        MaidDebug.log("Script 终止: " + this.ctx.scriptName + " → " + reason
                + "（已执行 " + this.ctx.stepLog.size() + " 步）");
    }

    /** 持续型：不因 MaidTaskManager 的 60s 上限被截断，总时长由脚本内部护栏控制。 */
    @Override
    public boolean isContinuous() {
        return true;
    }

    @Override
    public boolean isDone() {
        return this.done || this.cancelled;
    }

    @Override
    public String result() {
        if (this.cancelled) {
            return "脚本已取消";
        }
        if (this.ctx.terminateReason != null) {
            return this.ctx.terminateReason;
        }
        return this.failInfo != null ? "脚本执行失败" : "脚本完成";
    }
    @Override
    public void forceStop(SmartMaidEntity maid) {
        if (this.current != null) {
            this.current.forceStop(maid);
        }
        // 正常结束（done 已置位，如 terminate/全部步完成）不算"取消"；
        // 只有外部打断（cancel 指令 / 坐下 / 战斗 / 超时）时才标记 cancelled。
        if (!this.done) {
            this.cancelled = true;
        }
        this.done = true;
    }

    /** 最终汇总回执（Atomic Command Protocol §七） */
    @Override
    public JsonObject resultJson() {
        JsonObject out = new JsonObject();
        out.addProperty("task", "script");
        out.addProperty("name", this.ctx.scriptName);
        out.addProperty("ok", !this.cancelled && (this.failInfo == null || this.failPolicy == FAIL_CONTINUE));
        out.addProperty("state", this.cancelled ? "cancelled"
                : (this.ctx.terminated ? "terminated" : "done"));
        out.addProperty("reason", this.cancelled ? "脚本已取消"
                : (this.ctx.terminateReason != null ? this.ctx.terminateReason
                : (this.failInfo != null ? "步骤失败" : "完成")));
        out.addProperty("completed", this.ctx.stepLog.size());
        JsonObject vars = new JsonObject();
        for (Map.Entry<String, JsonElement> e : this.ctx.vars.entrySet()) {
            vars.add(e.getKey(), e.getValue().deepCopy());
        }
        out.add("vars", vars);
        JsonArray steps = new JsonArray();
        for (JsonObject s : this.ctx.stepLog) {
            steps.add(s.deepCopy());
        }
        out.add("steps", steps);
        if (this.failInfo != null) {
            out.add("failed", this.failInfo);
        }
        if (this.ctx.terminateResult != null) {
            out.add("terminated_result", this.ctx.terminateResult.deepCopy());
        }
        return out;
    }

    // ---------- 内部 ----------

    /** 执行帧：普通 step 列表 或 循环帧 */
    private static final class Frame {
        final List<JsonObject> steps;
        int index;
        final boolean isLoop;
        final JsonObject whileCond;
        final List<JsonObject> body;
        int iterLeft;

        Frame(List<JsonObject> steps) {
            this.steps = steps;
            this.index = 0;
            this.isLoop = false;
            this.whileCond = null;
            this.body = Collections.emptyList();
            this.iterLeft = 0;
        }

        Frame(JsonObject whileCond, List<JsonObject> body, int maxIter) {
            this.steps = Collections.emptyList();
            this.index = 0;
            this.isLoop = true;
            this.whileCond = whileCond;
            this.body = body;
            this.iterLeft = maxIter;
        }
    }

    private static List<JsonObject> jsonArrayToList(JsonArray arr) {
        List<JsonObject> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (JsonElement e : arr) {
            if (e.isJsonObject()) {
                out.add(e.getAsJsonObject());
            }
        }
        return out;
    }
}
