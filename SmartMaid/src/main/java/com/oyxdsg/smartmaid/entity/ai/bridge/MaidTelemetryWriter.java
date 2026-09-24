package com.oyxdsg.smartmaid.entity.ai.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import com.oyxdsg.smartmaid.entity.ai.MaidDebug;
import com.oyxdsg.smartmaid.entity.ai.maidtask.MaidAITask;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Stream;

/**
 * 女仆遥测下行（M5-a）：把女仆的感知事件按桌面宠物的既有格式落盘，
 * 让桌宠「看到」女仆，而不需要改造桌宠的数据通路。
 *
 * <p>写入位置：{@code <游戏目录>/deskpet/maid/YYYYMMDD-HHMM.jsonl}，每行一个窗口 JSON。
 * 目录刻意与 {@code deskpet/} 顶层隔离——顶层是 deskpet-mod 的写入区，混写会产生同分钟文件竞争。</p>
 *
 * <p>窗口 schema 对齐桌宠 {@code game/mod_data.py} 的既有约定：</p>
 * <pre>{@code
 * {"source":"smartmaid","seq":3,"tick":123456,"importance":"NORMAL",
 *  "highlights":[{"type":"damage","player":"女仆","target":"zombie","detail":"剩余生命 18.0"}],
 *  "maid":{"name":"女仆","owner":"Steve","dim":"...","pos":[x,y,z],"health":18.0,"task":"mine",...}}
 * }</pre>
 *
 * <p>{@code highlights[].type} 复用桌宠已认识的取值（damage / summary），
 * 因此桌宠侧 {@code highlights_to_text} 无需改动即可正确渲染中文描述；
 * 多出的 {@code maid} 字段桌宠会忽略，属于额外状态。</p>
 *
 * <p>配置：{@code config/smartmaid/bridge.json}，见 {@link BridgeConfig}。</p>
 *
 * <p>驱动：{@link SmartMaidEntity#aiStep()} 服务端分支每 tick 调用
 * {@link #onMaidTick(SmartMaidEntity)}。</p>
 */
public final class MaidTelemetryWriter {

    /** 旧文件保留时长（毫秒），与 WindowSink 一致 */
    private static final long RETENTION_MS = 2 * 60 * 1000L;
    /**
     * 首窗口提前落盘的 tick 数（5s）。
     * 默认窗口 20s，但女仆刚召唤时如果玩家很快退出/暂停，可能一个窗口都凑不满；
     * 首窗口缩短保证"召唤后 5 秒内必定能看到一份遥测"。
     */
    private static final int FIRST_WINDOW_TICKS = 100;
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final Gson GSON = new Gson();

    /** 未指定时的兜底显示名 */
    private static final String FALLBACK_NAME = "女仆";

    private static final Map<SmartMaidEntity, WindowBuffer> BUFFERS = new WeakHashMap<>();

    private static String currentMinute = "";
    private static Writer writer;

    private MaidTelemetryWriter() {
    }

    // ---------- 对外入口 ----------

    /** 每 tick 驱动（服务端）：累积事件，到窗口边界落盘一次 */
    public static void onMaidTick(SmartMaidEntity maid) {
        if (maid.level().isClientSide()) {
            return;
        }
        if (!BridgeConfig.enabled()) {
            return;
        }
        WindowBuffer buf = BUFFERS.get(maid);
        if (buf == null) {
            buf = new WindowBuffer();
            BUFFERS.put(maid, buf);
            // 事件订阅挂到感知模块：事件产生即入缓冲，不等快照
            final WindowBuffer target = buf;
            maid.getPerceptionModule().addEventSink(ev -> onEvent(target, ev));
        }
        buf.ticksInWindow++;
        // 首窗口提前落盘（5s），之后按配置周期（默认 20s）
        int limit = buf.seq == 0
                ? Math.min(BridgeConfig.windowTicks(), FIRST_WINDOW_TICKS)
                : BridgeConfig.windowTicks();
        if (buf.ticksInWindow >= limit) {
            flush(maid, buf);
        }
    }

    /** 女仆死亡/移除时清理（WeakHashMap 也会自动回收，此处为即时释放） */
    public static void forget(SmartMaidEntity maid) {
        BUFFERS.remove(maid);
        maid.getPerceptionModule().clearEventSinks();
    }

    // ---------- 事件 → highlight ----------

    private static void onEvent(WindowBuffer buf, JsonObject ev) {
        String type = str(ev, "type", "");
        JsonObject data = ev.has("data") && ev.get("data").isJsonObject()
                ? ev.getAsJsonObject("data") : new JsonObject();
        switch (type) {
            case "hurt" -> {
                // 复用桌宠已认识的 damage 类型 → 渲染为「[受伤] 女仆 被 zombie 伤害」
                JsonObject h = highlight("damage");
                h.addProperty("player", FALLBACK_NAME);
                String target = str(data, "attacker", "unknown");
                h.addProperty("target", target);
                h.addProperty("detail", "剩余生命 " + num(data, "health"));
                buf.highlights.add(h);
                // 攻击者未知（环境伤害 / 解析失败）→ 降级 NORMAL，避免误报"被谁打了"
                if ("unknown".equals(target)) {
                    buf.normal = true;
                } else {
                    buf.critical = true;
                }
            }
            case "enemy_spotted" -> {
                buf.highlights.add(summary("女仆发现 " + str(data, "type", "?")
                        + "（" + num(data, "dist") + " 格外）"));
                buf.critical = true;
            }
            case "environment_danger" -> {
                String danger = switch (str(data, "danger", "")) {
                    case "water" -> "水域";
                    case "lava" -> "岩浆";
                    case "fire" -> "火焰";
                    default -> "危险";
                };
                boolean enter = data.has("enter") && data.get("enter").getAsBoolean();
                buf.highlights.add(summary("女仆" + (enter ? "进入" : "离开") + danger));
                buf.critical = true;
            }
            case "task_started" -> {
                buf.highlights.add(summary("女仆开始执行 " + str(data, "task", "?")));
                buf.normal = true;
            }
            case "task_done" -> {
                buf.highlights.add(summary("女仆完成了 " + str(data, "task", "?")));
                buf.normal = true;
            }
            default -> buf.highlights.add(summary(type));
        }
    }

    // ---------- 窗口落盘 ----------

    private static void flush(SmartMaidEntity maid, WindowBuffer buf) {
        buf.ticksInWindow = 0;
        List<JsonObject> highlights = new ArrayList<>(buf.highlights);
        String importance = buf.critical ? "CRITICAL" : (buf.normal ? "NORMAL" : "LOW");
        buf.highlights.clear();
        buf.critical = false;
        buf.normal = false;
        buf.seq++;
        try {
            JsonObject win = new JsonObject();
            win.addProperty("source", SmartMaid.MOD_ID);
            win.addProperty("seq", buf.seq);
            win.addProperty("tick", maid.level().getLevelData().getGameTime());
            win.addProperty("time", LocalDateTime.now().toString());
            win.addProperty("importance", importance);
            JsonArray arr = new JsonArray();
            highlights.forEach(arr::add);
            win.add("highlights", arr);
            win.add("maid", maidBlock(maid));
            writeLine(win);
            MaidDebug.log("遥测窗口写入: " + importance + " 事件=" + highlights.size()
                    + " seq=" + buf.seq + " → " + BridgeConfig.deskpetDir());
        } catch (Exception e) {
            SmartMaid.LOGGER.warn("女仆遥测写入失败: {}", e.toString());
        }
    }

    /** 女仆当前状态块（窗口附带），桌宠可忽略；也是联调时最直观的排查对象 */
    private static JsonObject maidBlock(SmartMaidEntity maid) {
        JsonObject m = new JsonObject();
        m.addProperty("name", maid.baseName());
        m.addProperty("owner", maid.getOwner() == null ? null : maid.getOwner().getName().getString());
        m.addProperty("dim", maid.level().dimension().identifier().toString());
        JsonArray pos = new JsonArray();
        pos.add(round(maid.getX()));
        pos.add(round(maid.getY()));
        pos.add(round(maid.getZ()));
        m.add("pos", pos);
        m.addProperty("health", round(maid.getHealth()));
        m.addProperty("max_health", round(maid.getMaxHealth()));
        MaidAITask task = maid.getMaidTaskManager().currentTask();
        m.addProperty("task", task == null ? null : task.taskId());
        m.addProperty("ai_busy", maid.isAiBusy());
        m.addProperty("sitting", maid.isOrderedToSit());
        m.addProperty("on_ground", maid.onGround());
        m.addProperty("mainhand", MaidDebug.itemName(maid.getMainHandItem()));
        return m;
    }

    private static void writeLine(JsonObject win) throws IOException {
        String minute = LocalDateTime.now().format(FILE_FMT);
        Path dir = BridgeConfig.deskpetDir();
        if (!minute.equals(currentMinute)) {
            closeWriter();
            currentMinute = minute;
            Files.createDirectories(dir);
            writer = Files.newBufferedWriter(
                    dir.resolve(minute + ".jsonl"),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            cleanup(dir);
        }
        writer.write(GSON.toJson(win));
        writer.write('\n');
        writer.flush();
    }

    private static void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // 关闭失败不影响后续写入（下次轮转会新建 writer）
            }
            writer = null;
        }
    }

    /** 删除 2 分钟前的 .jsonl（桌宠只关心新鲜数据，避免无限堆积） */
    private static void cleanup(Path dir) {
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(p -> p.toString().endsWith(".jsonl"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() < cutoff;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 文件被桌宠占用时跳过，下个窗口再清
                        }
                    });
        } catch (IOException ignored) {
            // 目录尚不存在等情况：忽略
        }
    }

    // ---------- 小工具 ----------

    private static JsonObject highlight(String type) {
        JsonObject h = new JsonObject();
        h.addProperty("type", type);
        return h;
    }

    private static JsonObject summary(String detail) {
        JsonObject h = highlight("summary");
        h.addProperty("detail", detail);
        return h;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static String num(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "?";
    }

    private static double round(double v) {
        return Math.round(v * 10.0D) / 10.0D;
    }

    /** 单个女仆的窗口缓冲 */
    private static final class WindowBuffer {
        private final List<JsonObject> highlights = new ArrayList<>();
        private int ticksInWindow;
        private int seq;
        private boolean critical;
        private boolean normal;
    }
}
