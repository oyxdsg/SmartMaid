package com.oyxdsg.smartmaid.entity.ai.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 女仆任务配置持久化（M4）：保存 AI 最近下发的每个任务的参数，
 * 女仆重召唤后配置仍然保留，AI 可随时查询。
 *
 * <p>存到 {@code config/smartmaid/maids/&lt;玩家UUID&gt;.cfg}（JSON 文本）。
 * 与 {@code MaidDataManager} 的背包/装备存档（.dat）分离，互不影响。</p>
 */
public final class MaidTaskConfig {

    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("smartmaid").resolve("maids");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MaidTaskConfig() {
    }

    /** 保存某任务的配置（覆盖同任务旧值） */
    public static void save(SmartMaidEntity maid, String cmd, JsonObject params) {
        UUID owner = ownerOf(maid);
        if (owner == null) {
            return;
        }
        Map<String, JsonObject> config = readAll(owner);
        config.put(cmd, params);
        write(owner, config);
    }

    /** 读取某任务配置（无则返回 null） */
    public static JsonObject get(SmartMaidEntity maid, String cmd) {
        UUID owner = ownerOf(maid);
        return owner == null ? null : readAll(owner).get(cmd);
    }

    /** 读取全部任务配置 */
    public static Map<String, JsonObject> all(SmartMaidEntity maid) {
        UUID owner = ownerOf(maid);
        return owner == null ? new LinkedHashMap<>() : readAll(owner);
    }

    /** 清除全部任务配置 */
    public static void clear(SmartMaidEntity maid) {
        UUID owner = ownerOf(maid);
        if (owner == null) {
            return;
        }
        try {
            Files.deleteIfExists(fileFor(owner));
        } catch (Exception e) {
            SmartMaid.LOGGER.error("清除任务配置失败", e);
        }
    }

    private static Map<String, JsonObject> readAll(UUID owner) {
        Path file = fileFor(owner);
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            Map<String, JsonObject> config = new LinkedHashMap<>();
            root.entrySet().forEach(e -> {
                if (e.getValue().isJsonObject()) {
                    config.put(e.getKey(), e.getValue().getAsJsonObject());
                }
            });
            return config;
        } catch (Exception e) {
            SmartMaid.LOGGER.error("读取任务配置失败", e);
            return new LinkedHashMap<>();
        }
    }

    private static void write(UUID owner, Map<String, JsonObject> config) {
        try {
            Files.createDirectories(DIR);
            JsonObject root = new JsonObject();
            config.forEach(root::add);
            Files.write(fileFor(owner), GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            SmartMaid.LOGGER.error("保存任务配置失败", e);
        }
    }

    private static UUID ownerOf(SmartMaidEntity maid) {
        return maid.getOwnerReference() == null ? null : maid.getOwnerReference().getUUID();
    }

    private static Path fileFor(UUID owner) {
        return DIR.resolve(owner + ".cfg");
    }
}
