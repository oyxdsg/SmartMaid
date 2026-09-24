package com.oyxdsg.smartmaid.entity.ai.perception;

import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidAITask;
import com.oyxdsg.smartmaid.entity.ai.perception.sense.BlockSense;
import com.oyxdsg.smartmaid.entity.ai.perception.sense.EntitySense;
import com.oyxdsg.smartmaid.entity.ai.perception.sense.EnvironmentSense;
import com.oyxdsg.smartmaid.entity.ai.perception.sense.InventorySense;
import com.oyxdsg.smartmaid.entity.ai.perception.sense.OwnerSense;
import com.oyxdsg.smartmaid.entity.ai.perception.sense.SelfSense;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 感知模块：调度各感知通道采样、缓存当前快照、管理事件队列。
 *
 * <p>挂接位置：{@code SmartMaidEntity.aiStep} 服务端分支，每 tick 调用 {@link #tick()}。</p>
 *
 * <p>采样策略：各通道独立间隔（高频 self/安全，中频 owner/实体，低频 inventory/env/方块扫描），
 * 未到间隔的通道复用上一快照对应区块，保证快照每 tick 完整。</p>
 *
 * <p>事件队列：快照是"状态"，事件是"即时变化"——受伤 / 任务开始结束 / 发现敌人 /
 * 环境危险（水/岩浆/着火）通过对比前后状态检测，缓存最近 {@value #MAX_EVENTS} 条，
 * 随快照一起输出，M5 时也可作为独立 WebSocket event 消息推送。</p>
 */
public class PerceptionModule {

    /** 事件队列上限 */
    private static final int MAX_EVENTS = 16;

    /**
     * 快照「完整」所需的最小 tick 数：所有通道 intervalTicks 的最小公倍数
     * （self=5 / inventory=20 / owner=10 / nearby=10 / blocks=10 / env=40 → LCM=40）。
     * 此前 WS 首帧全量可能在快照残缺时发出（女仆刚出生、慢通道未采样），
     * 导致后续增量携带大量首帧缺失字段、diff 负收益。
     * 未达标时 current() 返回 null，WS 侧跳过首帧，保证全量基线必然完整。
     */
    private static final int READY_TICKS = 40;

    private final SmartMaidEntity maid;
    private final List<Sense> senses = new ArrayList<>();
    private final Deque<JsonObject> events = new ArrayDeque<>();
    private MaidPerception current;
    /** 事件订阅者（遥测下行 / WebSocket 推送等；可多个） */
    private final List<Consumer<JsonObject>> eventSinks = new ArrayList<>();

    // 事件检测的上一状态
    private int lastHurtTimestamp;
    private String lastTaskId;
    private MaidAITask lastTask;   // 记录任务实例，task_done 时读取结构化结果（如 script 最终回执）
    private boolean lastInWater;
    private boolean lastInLava;
    private boolean lastOnFire;
    private String lastHostileKey;

    public PerceptionModule(SmartMaidEntity maid) {
        this.maid = maid;
        // 通道注册顺序即快照区块构建顺序
        this.senses.add(new SelfSense());
        this.senses.add(new InventorySense());
        this.senses.add(new OwnerSense());
        this.senses.add(new EntitySense());
        this.senses.add(new BlockSense());
        this.senses.add(new EnvironmentSense());
    }

    /** 每 tick 驱动（服务端调用）：采样新快照 + 检测事件 + 缓存 */
    public void tick() {
        if (this.maid.level().isClientSide()) {
            return;
        }
        MaidPerception previous = this.current;
        MaidPerception data = new MaidPerception(this.maid);
        for (Sense sense : this.senses) {
            if (sense.intervalTicks() <= 1 || this.maid.tickCount % sense.intervalTicks() == 0) {
                sense.collect(this.maid, data);
            } else if (previous != null) {
                data.copySection(previous, sense.sectionName());
            }
        }
        this.detectEvents(data);
        data.setEvents(new ArrayList<>(this.events));
        // 未到「完整」tick 前不发布快照：避免 WS 首帧全量基于残缺快照
        // （慢通道 env/inventory 尚未采样），导致后续增量比全量还大。
        this.current = (this.maid.tickCount >= READY_TICKS) ? data : null;
        // 低频日志（每 40 tick）：全量快照体积大（5-6KB），属高噪日志由 verbose() 单独开启——
        // 默认不打以免数百小时游戏累积数 GB（latest.log 启动时才轮转）。
        // 需要观察感知数据时：MaidDebug.VERBOSE 改 true，或直接 /maidperception 手动打一次。
        if (MaidDebug.verbose() && this.maid.tickCount % 40 == 0) {
            MaidDebug.log("感知快照: " + data.toJson());
        }
    }

    /** 当前快照（客户端或首次采样前为 null） */
    public MaidPerception current() {
        return this.current;
    }

    /**
     * 立即采样一次**完整**快照（女仆生成时调用）：强制所有通道采集一次
     * （不按各自间隔、不受 {@link #READY_TICKS} 限制），并缓存为当前快照。
     *
     * <p>用途：女仆生成瞬间就有完整状态（含背包/装备/主人/周边），
     * WebSocket 侧据此立即推送全量给桌宠，桌宠不必等定时首帧。</p>
     *
     * @return 完整快照；客户端侧返回当前缓存（可能为 null）
     */
    public MaidPerception snapshotNow() {
        if (this.maid.level().isClientSide()) {
            return this.current;
        }
        MaidPerception data = new MaidPerception(this.maid);
        for (Sense sense : this.senses) {
            try {
                sense.collect(this.maid, data);
            } catch (Throwable t) {
                // 单通道失败不影响其它通道（如实体扫描异常）
            }
        }
        this.detectEvents(data);
        data.setEvents(new ArrayList<>(this.events));
        this.current = data;
        return data;
    }

    /** 事件队列拷贝（供 M5 WebSocket 独立推送） */
    public List<JsonObject> pendingEvents() {
        return new ArrayList<>(this.events);
    }

    /**
     * 订阅事件：每产生一条事件（受伤/任务开始结束/发现敌人/环境危险）时立即回调，
     * 供遥测下行、WebSocket event 消息等外部通道消费。同一回调只注册一次。
     */
    public void addEventSink(Consumer<JsonObject> sink) {
        if (sink != null && !this.eventSinks.contains(sink)) {
            this.eventSinks.add(sink);
        }
    }

    /** 取消全部事件订阅（女仆移除/死亡时调用） */
    public void clearEventSinks() {
        this.eventSinks.clear();
    }

    // ---------- 事件检测 ----------

    private void detectEvents(MaidPerception data) {
        // 受伤：lastHurtByMobTimestamp 变化（0 = 从未受伤）
        int hurtTs = this.maid.getLastHurtByMobTimestamp();
        if (hurtTs > 0 && hurtTs != this.lastHurtTimestamp) {
            this.lastHurtTimestamp = hurtTs;
            JsonObject p = new JsonObject();
            p.addProperty("attacker", attackerLabel(this.maid.getRecentAttacker()));
            p.addProperty("health", this.maid.getHealth());
            this.pushEvent("hurt", p);
        }
        // 任务开始/结束
        MaidAITask task = this.maid.getMaidTaskManager().currentTask();
        String taskId = task == null ? null : task.taskId();
        if (!Objects.equals(taskId, this.lastTaskId)) {
            if (this.lastTaskId != null && taskId == null) {
                JsonObject p = new JsonObject();
                p.addProperty("task", this.lastTaskId);
                // 任务结构化最终结果（script 脚本汇总回执等），随 task_done 事件上行
                if (this.lastTask != null) {
                    JsonObject rj = this.lastTask.resultJson();
                    if (rj != null) {
                        p.add("result", rj);
                    }
                }
                this.pushEvent("task_done", p);
            }
            if (taskId != null) {
                JsonObject p = new JsonObject();
                p.addProperty("task", taskId);
                this.pushEvent("task_started", p);
            }
            this.lastTaskId = taskId;
            this.lastTask = task;
        }
        // 环境危险：进入/离开水 / 岩浆 / 着火
        boolean inWater = this.maid.isInWater();
        boolean inLava = this.maid.isInLava();
        boolean onFire = this.maid.isOnFire();
        if (inWater != this.lastInWater) {
            JsonObject p = new JsonObject();
            p.addProperty("danger", "water");
            p.addProperty("enter", inWater);
            this.pushEvent("environment_danger", p);
            this.lastInWater = inWater;
        }
        if (inLava != this.lastInLava) {
            JsonObject p = new JsonObject();
            p.addProperty("danger", "lava");
            p.addProperty("enter", inLava);
            this.pushEvent("environment_danger", p);
            this.lastInLava = inLava;
        }
        if (onFire != this.lastOnFire) {
            JsonObject p = new JsonObject();
            p.addProperty("danger", "fire");
            p.addProperty("enter", onFire);
            this.pushEvent("environment_danger", p);
            this.lastOnFire = onFire;
        }
        // 发现新敌人（低频扫描，复用索敌逻辑）
        if (this.maid.tickCount % 20 == 0) {
            LivingEntity hostile = PerceptionBlockUtil.findNearestHostile(this.maid, 16);
            String key = hostile == null ? null
                    : hostile.getType().toShortString() + "@" + hostile.blockPosition();
            if (!Objects.equals(key, this.lastHostileKey)) {
                this.lastHostileKey = key;
                if (hostile != null) {
                    JsonObject p = new JsonObject();
                    p.addProperty("type", hostile.getType().toShortString());
                    p.addProperty("pos", hostile.blockPosition().toString());
                    p.addProperty("dist", Math.round(this.maid.distanceTo(hostile) * 10.0D) / 10.0D);
                    this.pushEvent("enemy_spotted", p);
                }
            }
        }
    }

    /**
     * 攻击者 → AI 可读标签（B 方案类型映射表）：
     * <ul>
     *     <li>null（环境伤害/缓存失效）→ {@code unknown}</li>
     *     <li>Player → 玩家名</li>
     *     <li>Projectile → 射手（递归映射；无射手则返回弹射物自身类型）</li>
     *     <li>其他实体 → {@code minecraft:<type>}</li>
     * </ul>
     */
    private static String attackerLabel(Entity attacker) {
        if (attacker == null) {
            return "unknown";
        }
        if (attacker instanceof Player p) {
            return p.getName().getString();
        }
        if (attacker instanceof Projectile projectile) {
            Entity owner = projectile.getOwner();
            return owner != null ? attackerLabel(owner) : attacker.getType().toShortString();
        }
        return attacker.getType().toShortString();
    }

    private void pushEvent(String type, JsonObject payload) {
        JsonObject ev = new JsonObject();
        ev.addProperty("t", this.maid.level().getLevelData().getGameTime());
        ev.addProperty("type", type);
        ev.add("data", payload == null ? new JsonObject() : payload);
        this.events.addLast(ev);
        while (this.events.size() > MAX_EVENTS) {
            this.events.removeFirst();
        }
        if (!this.eventSinks.isEmpty()) {
            for (Consumer<JsonObject> sink : this.eventSinks) {
                sink.accept(ev);
            }
        }
    }
}
