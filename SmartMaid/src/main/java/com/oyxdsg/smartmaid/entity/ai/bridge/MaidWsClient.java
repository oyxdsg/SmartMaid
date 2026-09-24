package com.oyxdsg.smartmaid.entity.ai.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.perception.MaidPerception;
import com.oyxdsg.smartmaid.entity.ai.perception.PerceptionDiff;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 桌宠联动上行/下行通道（M5-b）：模组作为 WebSocket Client 连接桌宠侧 Server
 * {@code ws://127.0.0.1:21420}，实现"女仆上报感知、桌宠下发指令"的闭环。
 *
 * <p>用 JDK 内置 {@code java.net.http.WebSocket}，不引入任何新依赖。</p>
 *
 * <h3>协议</h3>
 * 模组 → 桌宠：
 * <pre>{@code
 * {"type":"hello","mod":"smartmaid","version":"...","token":"...","maids":[{"id","name","owner"}]}
 * {"type":"perception","maid":"<uuid>","name":"女仆","owner":"Steve",
 *  "seq":3,"tick":123,"full":false,"changed":{...},"removed":["self.status.target"]}
 * {"type":"perception","maid":"<uuid>","seq":1,"tick":100,"full":true,"snapshot":{...}}
 * {"type":"event","maid":"<uuid>","event":{"t":100,"type":"hurt","data":{...}}}
 * {"type":"command_result","maid":"<uuid>","reply":{"id","ok","state","step","result"}}
 * {"type":"pong","t":123}
 * }</pre>
 *
 * 桌宠 → 模组：
 * <pre>{@code
 * {"type":"hello_ack","ok":true}
 * {"type":"command","maid":"","id":"c1","cmd":"mine","params":{"pos":[100,-60,-50],"range":4}}
 * {"type":"command","maid":"","json":"{...完整指令 JSON，直接透传...}"}
 * {"type":"speak","maid":"","text":"好的主人","ticks":80}
 * {"type":"animation","maid":"","name":"waving"}
 * {"type":"ping","t":123}
 * }</pre>
 *
 * <h3>线程模型（关键）</h3>
 * WebSocket 回调运行在 {@link HttpClient} 线程池，**绝不能直接触碰世界状态**。
 * 收到消息后一律先落地到 {@link #OUTBOX} 或经 {@code server.execute(...)} 切回服务端线程，
 * 再调用 {@link MaidAIBridge} / 实体 API。发送也统一由服务端 tick 从 OUTBOX 抽取，
 * 保证单线程串行写，避免多线程同时 {@code sendText} 造成分帧。
 *
 * <p>配置见 {@link BridgeConfig}（{@code ws.enabled/url/token/heartbeatSec/perceptionIntervalMs}）。</p>
 */
public final class MaidWsClient {

    private static final Gson GSON = new Gson();

    private static final long INITIAL_BACKOFF_MS = 1000L;
    private static final long MAX_BACKOFF_MS = 30000L;
    /** 单次 tick 最多发送的消息数，避免异常情况下刷屏 */
    private static final int MAX_SEND_PER_TICK = 64;

    private static final Object LOCK = new Object();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final Queue<String> OUTBOX = new ConcurrentLinkedQueue<>();

    /** 每女仆的通道状态（增量 diff 基线 + 上报节流） */
    private static final Map<SmartMaidEntity, Channel> CHANNELS = new WeakHashMap<>();

    private static volatile WebSocket socket;
    private static volatile boolean open;
    private static volatile boolean handshaken;
    private static boolean connecting;
    private static long nextAttemptAt;
    private static long backoffMs = INITIAL_BACKOFF_MS;
    private static long lastPingAt;
    private static boolean pongLogged;
    private static volatile MinecraftServer server;

    /** 动作名 → id（懒加载，构建失败时只支持数字 id） */
    private static Map<String, Integer> animIds;

    private MaidWsClient() {
    }

    // ---------- 生命周期 ----------

    public static void onServerStarted(MinecraftServer srv) {
        server = srv;
        synchronized (LOCK) {
            nextAttemptAt = 0;
            backoffMs = INITIAL_BACKOFF_MS;
            connecting = false;
        }
        if (BridgeConfig.wsEnabled()) {
            SmartMaid.LOGGER.info("女仆 WebSocket 已启用: {}", BridgeConfig.wsUrl());
        }
    }

    public static void onServerStopping(MinecraftServer srv) {
        WebSocket ws = socket;
        socket = null;
        open = false;
        handshaken = false;
        server = null;
        OUTBOX.clear();
        CHANNELS.clear();
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping");
            } catch (Exception ignored) {
                // 连接可能已断开，忽略
            }
        }
        SmartMaid.LOGGER.info("女仆 WebSocket 已停止");
    }

    public static boolean isConnected() {
        return open;
    }

    // ---------- 每 tick 驱动（服务端线程） ----------

    public static void onServerTick(MinecraftServer srv) {
        if (!BridgeConfig.wsEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!open) {
            tryConnect(now);
            return;
        }
        // 心跳
        if (now - lastPingAt >= BridgeConfig.heartbeatSec() * 1000L) {
            lastPingAt = now;
            JsonObject ping = new JsonObject();
            ping.addProperty("type", "ping");
            ping.addProperty("t", now);
            enqueue(ping);
        }
        // 感知上报
        for (Map.Entry<SmartMaidEntity, Channel> entry : new ArrayList<>(CHANNELS.entrySet())) {
            SmartMaidEntity maid = entry.getKey();
            if (!maid.isAlive() || maid.isRemoved()) {
                CHANNELS.remove(maid);
                continue;
            }
            sendPerception(maid, entry.getValue(), now);
        }
        drainOutbox();
    }

    /** 女仆侧注册（由 {@code SmartMaidEntity.aiStep} 调用），并把事件订阅挂到感知模块 */
    public static void onMaidTick(SmartMaidEntity maid) {
        if (maid.level().isClientSide() || !BridgeConfig.wsEnabled()) {
            return;
        }
        boolean first = !CHANNELS.containsKey(maid);
        Channel channel = CHANNELS.get(maid);
        if (channel == null) {
            channel = new Channel();
            CHANNELS.put(maid, channel);
            maid.getPerceptionModule().addEventSink(ev -> onMaidEvent(maid, ev));
        }
        if (first) {
            // 女仆生成：立即推送一次**全量**感知（含完整背包），桌宠一出现就有状态，
            // 不必等定时首帧（此前感知就绪前反查背包会误报「背包里没有」）。
            pushFullSnapshot(maid, channel);
        }
    }

    /**
     * 女仆生成时立即推送一次全量感知快照：重置 diff（强制 full）+ 强制全量采样 +
     * 立即发送。这样桌宠在女仆出现瞬间就拿到完整状态（背包/装备/主人/周边）。
     */
    private static void pushFullSnapshot(SmartMaidEntity maid, Channel channel) {
        try {
            channel.diff.reset();
            channel.lastPerceptionAt = 0;
            MaidPerception perception = maid.getPerceptionModule().snapshotNow();
            if (perception == null) {
                return;
            }
            sendPerception(maid, channel, perception);
            MaidDebug.log("女仆生成全量感知已推送: " + maid.baseName());
        } catch (Throwable t) {
            MaidDebug.log("女仆生成全量推送失败: " + t);
        }
    }

    // ---------- 对话 / 语音 / 存在通知（供网络包与服务端逻辑调用） ----------

    /** 玩家在聊天栏对女仆说的话 → 上行给桌宠 AI（服务端线程调用）。 */
    public static void sendChat(SmartMaidEntity maid, String text) {
        if (maid == null || text == null || text.isBlank()) {
            return;
        }
        JsonObject out = new JsonObject();
        out.addProperty("type", "chat");
        out.addProperty("maid", maid.getUUID().toString());
        out.addProperty("text", text);
        enqueue(out);
        MaidDebug.log("已上行玩家对话: " + text);
    }

    /**
     * 女仆存在通知（上线/离线）：桌宠侧据此决定显示或隐退（隐退开关在桌宠设置里）。
     */
    public static void notifyPresence(SmartMaidEntity maid, boolean online) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "maid_presence");
        if (maid != null) {
            out.addProperty("maid", maid.getUUID().toString());
            out.addProperty("name", maid.baseName());
        }
        out.addProperty("online", online);
        enqueue(out);
        MaidDebug.log("女仆 presence online=" + online
                + (maid == null ? "" : " " + maid.baseName()));
    }

    // ---------- 上行 ----------

    private static void onMaidEvent(SmartMaidEntity maid, JsonObject ev) {
        if (!open) {
            return;
        }
        JsonObject out = new JsonObject();
        out.addProperty("type", "event");
        out.addProperty("maid", maid.getUUID().toString());
        out.add("event", ev.deepCopy());
        enqueue(out);
    }

    private static void sendPerception(SmartMaidEntity maid, Channel channel, long now) {
        int interval = BridgeConfig.perceptionIntervalMs();
        if (now - channel.lastPerceptionAt < interval) {
            return;
        }
        MaidPerception perception = maid.getPerceptionModule().current();
        if (perception == null) {
            return;
        }
        channel.lastPerceptionAt = now;
        sendPerception(maid, channel, perception);
    }

    /** 组装并发出一条 perception 消息（增量 diff 字段平铺到信封上）。 */
    private static void sendPerception(SmartMaidEntity maid, Channel channel,
                                       MaidPerception perception) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "perception");
        message.addProperty("maid", maid.getUUID().toString());
        message.addProperty("name", maid.baseName());
        message.addProperty("owner", maid.getOwner() == null
                ? null : maid.getOwner().getName().getString());
        // 增量 diff 的字段（seq/tick/full/snapshot 或 changed/removed）平铺到信封上
        channel.diff.next(perception).entrySet()
                .forEach(e -> message.add(e.getKey(), e.getValue()));
        enqueue(message);
        if (channel.diff.seq() == 1) {
            // 只打一次：证明感知上报链路真的跑起来了（后续每 750ms 一条，不宜逐条打日志）
            MaidDebug.log("开始上报感知: " + maid.baseName()
                    + "（间隔 " + BridgeConfig.perceptionIntervalMs() + "ms，首帧全量）");
        }
    }

    private static void enqueue(JsonObject message) {
        if (OUTBOX.size() < 512) {
            OUTBOX.add(GSON.toJson(message));
        }
    }

    private static void drainOutbox() {
        WebSocket ws = socket;
        if (ws == null || !open) {
            return;
        }
        String payload;
        int sent = 0;
        while (sent < MAX_SEND_PER_TICK && (payload = OUTBOX.poll()) != null) {
            final String text = payload;
            try {
                ws.sendText(text, true).exceptionally(err -> {
                    handleDisconnect("发送失败 " + err);
                    return null;
                });
                sent++;
            } catch (Exception e) {
                // 连接已失效：把消息放回队首，等重连后重发
                OUTBOX.add(text);
                handleDisconnect("发送异常 " + e);
                return;
            }
        }
    }

    // ---------- 连接管理 ----------

    private static void tryConnect(long now) {
        synchronized (LOCK) {
            if (connecting || now < nextAttemptAt) {
                return;
            }
            connecting = true;
        }
        URI uri;
        try {
            uri = URI.create(BridgeConfig.wsUrl());
        } catch (IllegalArgumentException e) {
            synchronized (LOCK) {
                connecting = false;
                nextAttemptAt = now + MAX_BACKOFF_MS;
            }
            SmartMaid.LOGGER.warn("ws.url 非法，跳过本次连接: {}", BridgeConfig.wsUrl());
            return;
        }
        HTTP.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(uri, new Listener())
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        synchronized (LOCK) {
                            connecting = false;
                            nextAttemptAt = System.currentTimeMillis() + backoffMs;
                            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                        }
                        // 连接失败在单机下很常见（没开桌宠），只在 debug 级提示
                        MaidDebug.log("桌宠 WebSocket 连接失败: " + err);
                    }
                });
    }

    private static void onOpen(WebSocket ws) {
        synchronized (LOCK) {
            socket = ws;
            open = true;
            handshaken = false;
            connecting = false;
            backoffMs = INITIAL_BACKOFF_MS;
            lastPingAt = 0;
            pongLogged = false;
        }
        MaidDebug.log("桌宠 WebSocket 已连接: " + BridgeConfig.wsUrl());
        OUTBOX.clear();
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("mod", SmartMaid.MOD_ID);
        hello.addProperty("token", BridgeConfig.wsToken());
        JsonArray maids = new JsonArray();
        for (SmartMaidEntity maid : aliveMaids()) {
            JsonObject m = new JsonObject();
            m.addProperty("id", maid.getUUID().toString());
            m.addProperty("name", maid.baseName());
            m.addProperty("owner", maid.getOwner() == null
                    ? null : maid.getOwner().getName().getString());
            maids.add(m);
        }
        hello.add("maids", maids);
        enqueue(hello);
    }

    private static void handleDisconnect(String reason) {
        boolean wasOpen;
        synchronized (LOCK) {
            wasOpen = open;
            open = false;
            handshaken = false;
            socket = null;
            connecting = false;
            nextAttemptAt = System.currentTimeMillis() + backoffMs;
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        }
        if (wasOpen) {
            MaidDebug.log("桌宠 WebSocket 断开（" + reason + "），稍后重连");
        }
        // 重连后接收方状态已丢失：下次感知必须发全量
        MinecraftServer srv = server;
        if (srv != null) {
            srv.execute(() -> CHANNELS.values().forEach(c -> c.diff.reset()));
        }
    }

    // ---------- 下行分发（服务端线程） ----------

    private static void dispatch(String text) {
        JsonObject msg;
        try {
            msg = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            MaidDebug.log("桌宠下发了非法 JSON: " + e);
            return;
        }
        String type = str(msg, "type", "");
        switch (type) {
            case "hello_ack" -> {
                handshaken = true;
                MaidDebug.log("握手成功" + (ok(msg) ? "" : "（对方标记 ok=false）"));
                // 物品索引（当前语言的物品名 → id）：桌宠侧据此做中文/拼音模糊匹配，
                // 专用服务端拿不到语言资源时为 null，跳过即可。
                JsonObject index = ItemIndexPayload.build();
                if (index != null) {
                    enqueue(index);
                    MaidDebug.log("已下发物品索引 " + index.get("count").getAsInt() + " 条");
                }
            }
            case "ping" -> {
                JsonObject pong = new JsonObject();
                pong.addProperty("type", "pong");
                pong.addProperty("t", System.currentTimeMillis());
                enqueue(pong);
            }
            case "pong" -> {
                // 心跳应答：首次记录一次往返延迟，之后静默（避免每 5s 刷日志）
                long sentAt = msg.has("t") && msg.get("t").isJsonPrimitive()
                        && msg.get("t").getAsJsonPrimitive().isNumber() ? msg.get("t").getAsLong() : 0L;
                if (sentAt > 0 && !pongLogged) {
                    pongLogged = true;
                    MaidDebug.log("心跳往返 " + (System.currentTimeMillis() - sentAt) + "ms");
                }
            }
            case "command" -> handleCommand(msg);
            case "speak" -> handleSpeak(msg);
            case "chat_reply" -> handleChatReply(msg);
            case "animation" -> handleAnimation(msg);
            default -> MaidDebug.log("桌宠下发了未知消息类型: " + type);
        }
    }

    private static void handleCommand(JsonObject msg) {
        SmartMaidEntity maid = resolveMaid(str(msg, "maid", ""));
        if (maid == null) {
            SmartMaid.LOGGER.warn("桌宠指令找不到目标女仆: {}", msg);
            reply(maid, MaidCommandResult.fail(str(msg, "id", "ws"), "未找到目标女仆"));
            return;
        }
        MaidCommandResult result = MaidAIBridge.execute(maid, toCommandJson(msg));
        MaidDebug.log("桌宠指令 → " + result.toJson());
        reply(maid, result);
    }

    private static void reply(SmartMaidEntity maid, MaidCommandResult result) {
        JsonObject out = new JsonObject();
        out.addProperty("type", "command_result");
        if (maid != null) {
            out.addProperty("maid", maid.getUUID().toString());
        }
        out.add("reply", JsonParser.parseString(result.toJson()));
        enqueue(out);
    }

    /** 把桌宠消息转成 {@link MaidAIBridge} 认的标准指令 JSON（支持 json 字段直接透传） */
    private static String toCommandJson(JsonObject msg) {
        if (msg.has("json") && msg.get("json").isJsonPrimitive()) {
            return msg.get("json").getAsString();
        }
        JsonObject req = new JsonObject();
        req.addProperty("id", str(msg, "id", "ws-" + System.currentTimeMillis()));
        req.addProperty("cmd", str(msg, "cmd", ""));
        req.add("params", msg.has("params") && msg.get("params").isJsonObject()
                ? msg.getAsJsonObject("params") : new JsonObject());
        if (msg.has("cancel_previous") && msg.get("cancel_previous").isJsonPrimitive()) {
            req.addProperty("cancel_previous", msg.get("cancel_previous").getAsBoolean());
        }
        if (msg.has("persist") && msg.get("persist").isJsonPrimitive()) {
            req.addProperty("persist", msg.get("persist").getAsBoolean());
        }
        return req.toString();
    }

    private static void handleSpeak(JsonObject msg) {
        SmartMaidEntity maid = resolveMaid(str(msg, "maid", ""));
        String text = str(msg, "text", "");
        if (maid == null || text.isEmpty()) {
            return;
        }
        int ticks = msg.has("ticks") && msg.get("ticks").isJsonPrimitive()
                ? msg.get("ticks").getAsInt() : 80;
        maid.showBubble(Component.literal(text), ticks);
        MaidDebug.log("桌宠气泡: " + text);
    }

    /** AI 对话回复：头顶气泡 + 主人聊天栏回显。 */
    private static void handleChatReply(JsonObject msg) {
        SmartMaidEntity maid = resolveMaid(str(msg, "maid", ""));
        String text = str(msg, "text", "");
        if (text.isEmpty()) {
            return;
        }
        if (maid != null) {
            int ticks = Math.max(60, Math.min(200, text.length() * 10));
            maid.showBubble(Component.literal(text), ticks);
            if (maid.getOwner() instanceof ServerPlayer player) {
                player.sendSystemMessage(Component.literal("女仆：" + text));
            }
        }
        MaidDebug.log("AI 回复: " + text);
    }

    private static void handleAnimation(JsonObject msg) {
        SmartMaidEntity maid = resolveMaid(str(msg, "maid", ""));
        if (maid == null) {
            return;
        }
        int id;
        if (msg.has("id") && msg.get("id").isJsonPrimitive()) {
            id = msg.get("id").getAsInt();
        } else {
            Integer resolved = animId(str(msg, "name", ""));
            if (resolved == null) {
                MaidDebug.log("桌宠动画名未知: " + str(msg, "name", ""));
                return;
            }
            id = resolved;
        }
        maid.setDebugAnim(id);
        MaidDebug.log("桌宠动画 → id " + id);
    }

    // ---------- 目标女仆定位 ----------

    /** 按 uuid 定位女仆；uuid 为空时退化为「唯一存活女仆」 */
    private static SmartMaidEntity resolveMaid(String uuidText) {
        List<SmartMaidEntity> alive = aliveMaids();
        if (!uuidText.isBlank()) {
            try {
                UUID uuid = UUID.fromString(uuidText);
                MinecraftServer srv = server;
                if (srv != null) {
                    for (ServerLevel level : srv.getAllLevels()) {
                        Entity entity = level.getEntity(uuid);
                        if (entity instanceof SmartMaidEntity maid) {
                            return maid;
                        }
                    }
                }
            } catch (IllegalArgumentException e) {
                MaidDebug.log("桌宠给的 maid id 不是合法 UUID: " + uuidText);
            }
            return null;
        }
        if (alive.isEmpty()) {
            return null;
        }
        if (alive.size() > 1) {
            MaidDebug.log("存在 " + alive.size() + " 只女仆且指令未指定 maid，取第一只");
        }
        return alive.get(0);
    }

    private static List<SmartMaidEntity> aliveMaids() {
        List<SmartMaidEntity> list = new ArrayList<>();
        for (SmartMaidEntity maid : CHANNELS.keySet()) {
            if (maid.isAlive() && !maid.isRemoved()) {
                list.add(maid);
            }
        }
        return list;
    }

    // ---------- 工具 ----------

    private static Integer animId(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        if (animIds == null) {
            animIds = new HashMap<>();
            try {
                Class<?> cls = Class.forName(
                        "com.oyxdsg.smartmaid.client.animation.MaidAnimManager");
                String[] names = (String[]) cls.getMethod("getAnimNames").invoke(null);
                for (int i = 0; i < names.length; i++) {
                    if (names[i] != null && !names[i].isEmpty()) {
                        animIds.put(names[i].toLowerCase(), i);
                    }
                }
            } catch (Throwable t) {
                // 专用服务端没有客户端类：只支持数字 id
                SmartMaid.LOGGER.warn("动画名解析不可用（仅支持数字 id）: {}", t.toString());
            }
        }
        return animIds.get(name.toLowerCase());
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static boolean ok(JsonObject o) {
        return !o.has("ok") || o.get("ok").getAsBoolean();
    }

    /** 每女仆的通道状态 */
    private static final class Channel {
        private final PerceptionDiff diff = new PerceptionDiff();
        private long lastPerceptionAt;
    }

    /** WebSocket 监听：只做拼接与线程切换，不碰世界状态 */
    private static final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            MaidWsClient.onOpen(ws);
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            this.buffer.append(data);
            if (last) {
                String text = this.buffer.toString();
                this.buffer.setLength(0);
                MinecraftServer srv = server;
                if (srv != null) {
                    // 关键：切回服务端线程，避免 WebSocket 线程触碰世界状态
                    srv.execute(() -> dispatch(text));
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            handleDisconnect("对方关闭 " + statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            handleDisconnect("异常 " + error);
        }
    }
}
