package com.oyxdsg.smartmaid.entity.ai.perception;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;

import java.util.List;

/**
 * 感知快照：AI 的"玩家视角"世界/自身状态数据模型。
 *
 * <p>以 Gson {@link JsonObject} 为载体：既是 JSON（可直接序列化为字符串发给 WebSocket AI），
 * 也可作为 Java 对象被本地任务/规则系统查询（见 {@link PerceptionModule#current()}）。</p>
 *
 * <p>顶层结构：</p>
 * <ul>
 *     <li>{@code tick}/{@code dim} — 采样世界 tick 与维度</li>
 *     <li>{@code self} — 女仆自身状态/装备/安全/动作</li>
 *     <li>{@code inventory} — 女仆背包（41 格摘要 + 能力摘要）</li>
 *     <li>{@code owner} — 主人玩家状态</li>
 *     <li>{@code nearby} — 周边实体（敌对/动物/掉落物/玩家）</li>
 *     <li>{@code blocks} — 脚下方块/视线/容器/矿石/作物/危险方块</li>
 *     <li>{@code env} — 维度/生物群系/昼夜/天气/光照/区块</li>
 *     <li>{@code events} — 最近事件队列（受伤/任务/发现敌人/环境危险）</li>
 * </ul>
 */
public final class MaidPerception {

    private static final Gson GSON = new Gson();

    /** 采样时的世界 tick（服务端游戏时间） */
    public final long tick;

    private final JsonObject root = new JsonObject();

    public MaidPerception(SmartMaidEntity maid) {
        this.tick = maid.level().getLevelData().getGameTime();
        this.root.addProperty("tick", this.tick);
        this.root.addProperty("dim", maid.level().dimension().identifier().toString());
        this.root.add("self", new JsonObject());
        this.root.add("inventory", new JsonObject());
        this.root.add("owner", new JsonObject());
        this.root.add("nearby", new JsonObject());
        this.root.add("blocks", new JsonObject());
        this.root.add("env", new JsonObject());
        this.root.add("events", new JsonArray());
    }

    public JsonObject self() {
        return this.root.getAsJsonObject("self");
    }

    public JsonObject inventory() {
        return this.root.getAsJsonObject("inventory");
    }

    public JsonObject owner() {
        return this.root.getAsJsonObject("owner");
    }

    public JsonObject nearby() {
        return this.root.getAsJsonObject("nearby");
    }

    public JsonObject blocks() {
        return this.root.getAsJsonObject("blocks");
    }

    public JsonObject env() {
        return this.root.getAsJsonObject("env");
    }

    public JsonArray events() {
        return this.root.getAsJsonArray("events");
    }

    /** 写入事件列表（模块从事件队列拷贝） */
    public void setEvents(List<JsonObject> eventList) {
        JsonArray arr = new JsonArray();
        eventList.forEach(arr::add);
        this.root.add("events", arr);
    }

    /** 把其它快照的同名区块整体拷贝过来（未到采样间隔时保留上一份数据） */
    public void copySection(MaidPerception other, String sectionName) {
        JsonObject src = other.root.getAsJsonObject(sectionName);
        if (src != null) {
            this.root.add(sectionName, src);
        }
    }

    public JsonObject root() {
        return this.root;
    }

    /** 序列化为 JSON 字符串（发给 AI / 落日志） */
    public String toJson() {
        return GSON.toJson(this.root);
    }
}
