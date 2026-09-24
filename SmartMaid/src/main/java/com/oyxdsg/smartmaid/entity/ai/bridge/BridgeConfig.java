package com.oyxdsg.smartmaid.entity.ai.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.oyxdsg.smartmaid.SmartMaid;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 桌宠桥接配置：{@code config/smartmaid/bridge.json}（首次运行自动生成默认配置）。
 *
 * <pre>{@code
 * {
 *   "enabled": true,                      // 总开关（遥测下行）
 *   "deskpetDir": "",                     // 遥测目录，留空 = <游戏目录>/deskpet/maid
 *   "windowTicks": 400,                   // 遥测窗口聚合周期（20s）
 *   "ws": {
 *     "enabled": true,                    // 上行通道总开关
 *     "url": "ws://127.0.0.1:21420",      // 桌宠侧 WebSocket Server
 *     "token": "",                        // 握手 token，与桌宠配置一致（留空 = 不校验）
 *     "heartbeatSec": 5,
 *     "perceptionIntervalMs": 750         // 感知快照上报间隔
 *   }
 * }
 * }</pre>
 *
 * <p>加载时机：首次访问任意 getter 时懒加载一次（服务端线程调用）。</p>
 */
public final class BridgeConfig {

    private static final Gson GSON = new Gson();
    private static final String FILE_NAME = "smartmaid/bridge.json";

    private static final int DEFAULT_WINDOW_TICKS = 400;

    private static boolean loaded;
    private static boolean enabled = true;
    private static Path deskpetDir;
    private static int windowTicks = DEFAULT_WINDOW_TICKS;
    private static boolean wsEnabled = true;
    private static String wsUrl = "ws://127.0.0.1:21420";
    private static String wsToken = "";
    private static int heartbeatSec = 5;
    private static int perceptionIntervalMs = 750;

    private BridgeConfig() {
    }

    /** 懒加载配置（幂等） */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        JsonObject cfg;
        if (Files.isRegularFile(file)) {
            JsonObject parsed = null;
            try {
                parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject();
            } catch (IOException | JsonSyntaxException | IllegalStateException e) {
                SmartMaid.LOGGER.warn("bridge.json 解析失败，使用默认配置: {}", e.toString());
            }
            cfg = parsed == null ? defaults() : parsed;
        } else {
            cfg = defaults();
            writeDefault(file, cfg);
        }
        apply(cfg);
    }

    private static JsonObject defaults() {
        JsonObject cfg = new JsonObject();
        cfg.addProperty("enabled", true);
        cfg.addProperty("deskpetDir", "");
        cfg.addProperty("windowTicks", DEFAULT_WINDOW_TICKS);
        JsonObject ws = new JsonObject();
        ws.addProperty("enabled", true);
        ws.addProperty("url", "ws://127.0.0.1:21420");
        ws.addProperty("token", "");
        ws.addProperty("heartbeatSec", 5);
        ws.addProperty("perceptionIntervalMs", 750);
        cfg.add("ws", ws);
        return cfg;
    }

    private static void writeDefault(Path file, JsonObject cfg) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(cfg), StandardCharsets.UTF_8);
            SmartMaid.LOGGER.info("已生成默认桥接配置: {}", file);
        } catch (IOException e) {
            SmartMaid.LOGGER.warn("bridge.json 写入失败: {}", e.toString());
        }
    }

    private static void apply(JsonObject cfg) {
        enabled = !cfg.has("enabled") || cfg.get("enabled").getAsBoolean();
        if (cfg.has("windowTicks") && cfg.get("windowTicks").isJsonPrimitive()) {
            windowTicks = Math.max(20, cfg.get("windowTicks").getAsInt());
        }
        // 遥测目录：留空则用 <游戏目录>/deskpet/maid（桌宠按 <mc>/.minecraft/deskpet 推导，天然对齐）
        String custom = cfg.has("deskpetDir") ? cfg.get("deskpetDir").getAsString() : "";
        deskpetDir = (custom != null && !custom.isBlank())
                ? Path.of(custom)
                : FabricLoader.getInstance().getGameDir().resolve("deskpet").resolve("maid");

        if (cfg.has("ws") && cfg.get("ws").isJsonObject()) {
            JsonObject ws = cfg.getAsJsonObject("ws");
            wsEnabled = !ws.has("enabled") || ws.get("enabled").getAsBoolean();
            if (ws.has("url")) {
                wsUrl = ws.get("url").getAsString();
            }
            if (ws.has("token")) {
                wsToken = ws.get("token").getAsString();
            }
            if (ws.has("heartbeatSec") && ws.get("heartbeatSec").isJsonPrimitive()) {
                heartbeatSec = Math.max(1, ws.get("heartbeatSec").getAsInt());
            }
            if (ws.has("perceptionIntervalMs") && ws.get("perceptionIntervalMs").isJsonPrimitive()) {
                perceptionIntervalMs = Math.max(100, ws.get("perceptionIntervalMs").getAsInt());
            }
        }
    }

    public static boolean enabled() {
        load();
        return enabled;
    }

    public static Path deskpetDir() {
        load();
        return deskpetDir;
    }

    public static int windowTicks() {
        load();
        return windowTicks;
    }

    public static boolean wsEnabled() {
        load();
        return wsEnabled;
    }

    public static String wsUrl() {
        load();
        return wsUrl;
    }

    public static String wsToken() {
        load();
        return wsToken;
    }

    public static int heartbeatSec() {
        load();
        return heartbeatSec;
    }

    public static int perceptionIntervalMs() {
        load();
        return perceptionIntervalMs;
    }
}
