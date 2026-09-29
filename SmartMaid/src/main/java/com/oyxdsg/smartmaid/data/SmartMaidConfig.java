package com.oyxdsg.smartmaid.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.oyxdsg.smartmaid.SmartMaid;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 整合包/服务器友好的全局配置：{@code config/smartmaid/main.json}（首次运行自动生成）。
 *
 * <pre>{@code
 * {
 *   "commandPermission": "auto",     // auto | all | moderators | gamemasters
 *   "debug": false,                  // 事件级调试日志（排查问题改 true，不用重编译）
 *   "debugVerbose": false,           // 高频调试日志（需 debug 同时为 true）
 *   "bridgeBlocksBlacklist": [],     // 搭路禁止消耗的方块 id（叠加在白名单 tag 之上）
 *   "combatNeverTarget": [],         // 女仆绝不主动攻击的实体类型 id
 *   "craftWhitelistMode": "off",     // off | vanilla-only | tag
 *   "autoTidyWhenFull": true         // 背包满时自动整理（合并同类 + 丢弃 #smartmaid:junk）
 * }
 * }</pre>
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li>所有字段都有安全默认值；文件缺失/解析失败一律走默认 —— <b>绝不因为配置问题让游戏起不来</b>。</li>
 *   <li>{@code commandPermission=auto}（默认）：单人（集成服务器）用 {@link Commands#LEVEL_ALL}
 *       —— 整合包玩家不开作弊也能召唤；专用服务器保持 {@link Commands#LEVEL_GAMEMASTERS}。</li>
 *   <li>非法 id 只记 warning 并跳过，不抛异常。</li>
 * </ul>
 */
public final class SmartMaidConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "smartmaid/main.json";

    private static boolean loaded;
    private static String commandPermission = "auto";
    private static boolean debug = false;
    private static boolean debugVerbose = false;
    private static final Set<Block> bridgeBlocksBlacklist = new HashSet<>();
    private static final Set<EntityType<?>> combatNeverTarget = new HashSet<>();
    private static String craftWhitelistMode = "off";
    private static boolean autoTidyWhenFull = true;

    private SmartMaidConfig() {
    }

    /** 懒加载（幂等） */
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
                SmartMaid.LOGGER.warn("main.json 解析失败，使用默认配置: {}", e.toString());
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
        cfg.addProperty("commandPermission", "auto");
        cfg.addProperty("debug", false);
        cfg.addProperty("debugVerbose", false);
        cfg.add("bridgeBlocksBlacklist", new JsonArray());
        cfg.add("combatNeverTarget", new JsonArray());
        cfg.addProperty("craftWhitelistMode", "off");
        cfg.addProperty("autoTidyWhenFull", true);
        return cfg;
    }

    private static void writeDefault(Path file, JsonObject cfg) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(cfg), StandardCharsets.UTF_8);
            SmartMaid.LOGGER.info("已生成默认配置: {}", file);
        } catch (IOException e) {
            SmartMaid.LOGGER.warn("main.json 写入失败: {}", e.toString());
        }
    }

    private static void apply(JsonObject cfg) {
        if (cfg.has("commandPermission") && cfg.get("commandPermission").isJsonPrimitive()) {
            commandPermission = cfg.get("commandPermission").getAsString();
        }
        if (cfg.has("debug") && cfg.get("debug").isJsonPrimitive()) {
            debug = cfg.get("debug").getAsBoolean();
        }
        if (cfg.has("debugVerbose") && cfg.get("debugVerbose").isJsonPrimitive()) {
            debugVerbose = cfg.get("debugVerbose").getAsBoolean();
        }
        bridgeBlocksBlacklist.clear();
        for (Identifier id : readIds(cfg, "bridgeBlocksBlacklist")) {
            BuiltInRegistries.BLOCK.getOptional(id).ifPresentOrElse(
                    bridgeBlocksBlacklist::add,
                    () -> SmartMaid.LOGGER.warn("bridgeBlocksBlacklist 未知方块: {}", id));
        }
        combatNeverTarget.clear();
        for (Identifier id : readIds(cfg, "combatNeverTarget")) {
            BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresentOrElse(
                    combatNeverTarget::add,
                    () -> SmartMaid.LOGGER.warn("combatNeverTarget 未知实体: {}", id));
        }
        if (cfg.has("craftWhitelistMode") && cfg.get("craftWhitelistMode").isJsonPrimitive()) {
            craftWhitelistMode = cfg.get("craftWhitelistMode").getAsString().toLowerCase(Locale.ROOT);
        }
        if (cfg.has("autoTidyWhenFull") && cfg.get("autoTidyWhenFull").isJsonPrimitive()) {
            autoTidyWhenFull = cfg.get("autoTidyWhenFull").getAsBoolean();
        }
    }

    private static Set<Identifier> readIds(JsonObject cfg, String key) {
        Set<Identifier> out = new HashSet<>();
        if (!cfg.has(key) || !cfg.get(key).isJsonArray()) {
            return out;
        }
        for (JsonElement el : cfg.getAsJsonArray(key)) {
            if (!el.isJsonPrimitive()) {
                continue;
            }
            Identifier id = Identifier.tryParse(el.getAsString().trim());
            if (id == null) {
                SmartMaid.LOGGER.warn("{} 中的 id 非法: {}", key, el.getAsString());
                continue;
            }
            out.add(id);
        }
        return out;
    }

    /**
     * 命令权限等级。
     *
     * @param selection 命令注册时的环境（{@code CommandRegistrationCallback} 第三个参数）
     */
    public static PermissionCheck commandPermission(Commands.CommandSelection selection) {
        load();
        return switch (commandPermission) {
            case "all" -> Commands.LEVEL_ALL;
            case "moderators" -> Commands.LEVEL_MODERATORS;
            case "gamemasters" -> Commands.LEVEL_GAMEMASTERS;
            // auto（默认）：单人开箱可用，专用服务器保持管理员级
            default -> selection == Commands.CommandSelection.INTEGRATED
                    ? Commands.LEVEL_ALL
                    : Commands.LEVEL_GAMEMASTERS;
        };
    }

    /** 搭路禁止消耗的方块（叠加在白名单 tag 之上） */
    public static Set<Block> bridgeBlocksBlacklist() {
        load();
        return bridgeBlocksBlacklist;
    }

    /** 绝不主动攻击的实体类型 */
    public static Set<EntityType<?>> combatNeverTarget() {
        load();
        return combatNeverTarget;
    }

    /** 合成白名单模式：off / vanilla-only / tag */
    public static String craftWhitelistMode() {
        load();
        return craftWhitelistMode;
    }

    /** 背包满时自动整理（合并同类堆叠 + 丢弃 {@code #smartmaid:junk}） */
    public static boolean autoTidyWhenFull() {
        load();
        return autoTidyWhenFull;
    }

    /** 事件级调试日志开关（发布版默认关；排查问题时改 true，**不用重新编译**） */
    public static boolean debug() {
        load();
        return debug;
    }

    /** 高频调试日志开关（地形俯视图等；需 {@link #debug()} 同时为 true） */
    public static boolean debugVerbose() {
        load();
        return debugVerbose;
    }
}
