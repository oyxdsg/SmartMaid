package com.oyxdsg.smartmaid.entity.ai.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/**
 * 感知增量 diff（M-P1）：只输出相对上一份快照的变化字段，显著省 AI token。
 *
 * <p>快照里体积大头是 {@code blocks}（方块扫描）与 {@code nearby}（周边实体），
 * 女仆静止待命时几乎不变，全量重复上报是纯浪费。</p>
 *
 * <p>输出结构：</p>
 * <pre>{@code
 * // 首帧（或 reset 之后）：全量
 * {"seq":1,"tick":123,"full":true,"snapshot":{ ...完整快照... }}
 *
 * // 后续：增量（结构保持嵌套，接收方递归合并即可）
 * {"seq":2,"tick":143,"full":false,
 *  "changed":{"self":{"health":18.0,"status":{"task_id":"mine"}}},
 *  "removed":["self.status.target"]}
 * }</pre>
 *
 * <p>约定：</p>
 * <ul>
 *     <li>{@code changed} 保持原嵌套结构，接收方递归覆盖即可；数组按整体比较（变化即整段下发）</li>
 *     <li>{@code removed} 是「上一份有、这一份没有」的字段路径（点分格式，如 {@code self.status.target}）</li>
 *     <li>{@code tick} 与 {@code events} 不参与 diff：前者每次必变属噪声（放信封顶层），
 *         后者由独立的 {@code event} 消息推送</li>
 * </ul>
 *
 * <p>每个女仆一个实例（持有上一份快照），非线程安全，只在服务端线程使用。</p>
 */
public final class PerceptionDiff {

    /** 不参与 diff 的顶层字段 */
    private static final Set<String> SKIP_KEYS = Set.of("tick", "events");

    private JsonObject previous;
    private long seq;

    /**
     * 生成下一条消息：首次调用自动退化为全量，之后为增量。
     *
     * @return 可直接序列化下发（或落盘）的 JSON
     */
    public JsonObject next(MaidPerception perception) {
        JsonObject snapshot = perception.root();
        JsonObject out = new JsonObject();
        out.addProperty("seq", ++this.seq);
        out.addProperty("tick", perception.tick);

        if (this.previous == null) {
            out.addProperty("full", true);
            out.add("snapshot", snapshot);
            this.previous = snapshot.deepCopy();
            return out;
        }

        JsonObject changed = new JsonObject();
        JsonArray removed = new JsonArray();
        diff(this.previous, snapshot, changed, removed, "");
        out.addProperty("full", false);
        out.add("changed", changed);
        out.add("removed", removed);
        this.previous = snapshot.deepCopy();
        return out;
    }

    /** 强制下一次为全量（重连 / 接收方状态丢失时调用） */
    public void reset() {
        this.previous = null;
        this.seq = 0;
    }

    public long seq() {
        return this.seq;
    }

    public boolean hasBaseline() {
        return this.previous != null;
    }

    // ---------- 递归比较 ----------

    private static void diff(JsonObject prev, JsonObject cur,
                             JsonObject changed, JsonArray removed, String prefix) {
        for (Map.Entry<String, JsonElement> entry : cur.entrySet()) {
            String key = entry.getKey();
            if (prefix.isEmpty() && SKIP_KEYS.contains(key)) {
                continue;
            }
            JsonElement curVal = entry.getValue();
            JsonElement prevVal = prev.get(key);
            if (prevVal == null) {
                changed.add(key, curVal.deepCopy());
            } else if (prevVal.isJsonObject() && curVal.isJsonObject()) {
                JsonObject subChanged = new JsonObject();
                JsonArray subRemoved = new JsonArray();
                diff(prevVal.getAsJsonObject(), curVal.getAsJsonObject(),
                        subChanged, subRemoved, prefix + key + ".");
                if (!subChanged.isEmpty()) {
                    changed.add(key, subChanged);
                }
                subRemoved.forEach(removed::add);
            } else if (!prevVal.equals(curVal)) {
                changed.add(key, curVal.deepCopy());
            }
        }
        for (Map.Entry<String, JsonElement> entry : prev.entrySet()) {
            String key = entry.getKey();
            if (prefix.isEmpty() && SKIP_KEYS.contains(key)) {
                continue;
            }
            if (!cur.has(key)) {
                removed.add(prefix + key);
            }
        }
    }

    // ---------- 接收方合并（供桌宠侧对照实现 / 文档说明用） ----------

    /**
     * 把一条增量合并进基线快照（就地修改 {@code base}）。
     *
     * <p>桌宠侧（Python）实现同一语义即可；此处给出 Java 版参考实现，
     * 也便于将来在模组内自测。</p>
     */
    public static void merge(JsonObject base, JsonObject message) {
        if (message.has("full") && message.get("full").getAsBoolean()) {
            JsonObject snapshot = message.getAsJsonObject("snapshot");
            new ArrayList<>(base.keySet()).forEach(base::remove);
            snapshot.entrySet().forEach(e -> base.add(e.getKey(), e.getValue().deepCopy()));
            return;
        }
        if (message.has("changed") && message.get("changed").isJsonObject()) {
            mergeInto(base, message.getAsJsonObject("changed"));
        }
        if (message.has("removed")) {
            for (JsonElement e : message.getAsJsonArray("removed")) {
                removePath(base, e.getAsString());
            }
        }
    }

    private static void mergeInto(JsonObject base, JsonObject changed) {
        for (Map.Entry<String, JsonElement> entry : changed.entrySet()) {
            JsonElement val = entry.getValue();
            JsonElement exist = base.get(entry.getKey());
            if (val.isJsonObject() && exist != null && exist.isJsonObject()) {
                mergeInto(exist.getAsJsonObject(), val.getAsJsonObject());
            } else {
                base.add(entry.getKey(), val.deepCopy());
            }
        }
    }

    private static void removePath(JsonObject base, String path) {
        int dot = path.lastIndexOf('.');
        JsonObject parent = base;
        if (dot > 0) {
            String[] parts = path.substring(0, dot).split("\\.");
            for (String part : parts) {
                JsonElement next = parent.get(part);
                if (next == null || !next.isJsonObject()) {
                    return;
                }
                parent = next.getAsJsonObject();
            }
        }
        parent.remove(dot > 0 ? path.substring(dot + 1) : path);
    }
}
