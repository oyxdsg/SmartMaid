package com.oyxdsg.smartmaid.entity.ai.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 女仆任务配置持久化（M4）：保存 AI 最近下发的每个任务的参数，
 * 女仆重召唤后配置仍然保留，AI 可随时查询。
 *
 * <p><b>2026-09-29（整合包兼容）</b>：与 {@link com.oyxdsg.smartmaid.data.MaidDataManager}
 * 同步，存储位置从全局 {@code config/smartmaid/maids/} 改为<b>存档目录</b>
 * {@code <存档>/smartmaid/maids/<玩家UUID>.cfg}，避免多存档之间数据串档。
 * 旧文件在首次读取时一次性复制过来（复制失败则回退旧路径，不丢数据）。</p>
 */
public final class MaidTaskConfig {

    /** 旧位置（全局 config）：仅用于一次性迁移与兜底 */
    private static final Path LEGACY_DIR =
            FabricLoader.getInstance().getConfigDir().resolve("smartmaid").resolve("maids");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MaidTaskConfig() {
    }

    /** 保存某任务的配置（覆盖同任务旧值） */
    public static void save(SmartMaidEntity maid, String cmd, JsonObject params) {
        UUID owner = ownerOf(maid);
        if (owner == null) {
            return;
        }
        Map<String, JsonObject> config = readAll(maid, owner);
        config.put(cmd, params);
        write(maid, owner, config);
    }

    /** 读取某任务配置（无则返回 null） */
    public static JsonObject get(SmartMaidEntity maid, String cmd) {
        UUID owner = ownerOf(maid);
        return owner == null ? null : readAll(maid, owner).get(cmd);
    }

    /** 读取全部任务配置 */
    public static Map<String, JsonObject> all(SmartMaidEntity maid) {
        UUID owner = ownerOf(maid);
        return owner == null ? new LinkedHashMap<>() : readAll(maid, owner);
    }

    /** 清除全部任务配置 */
    public static void clear(SmartMaidEntity maid) {
        UUID owner = ownerOf(maid);
        if (owner == null) {
            return;
        }
        try {
            Files.deleteIfExists(fileFor(maid, owner));
            // 旧位置一并清理，避免下次读取又把旧配置迁移回来
            Files.deleteIfExists(LEGACY_DIR.resolve(owner + ".cfg"));
        } catch (Exception e) {
            SmartMaid.LOGGER.error("清除任务配置失败", e);
        }
    }

    private static Map<String, JsonObject> readAll(SmartMaidEntity maid, UUID owner) {
        Path file = fileFor(maid, owner);
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

    private static void write(SmartMaidEntity maid, UUID owner, Map<String, JsonObject> config) {
        try {
            Path file = fileFor(maid, owner);
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            config.forEach(root::add);
            Files.write(file, GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            SmartMaid.LOGGER.error("保存任务配置失败", e);
        }
    }

    private static UUID ownerOf(SmartMaidEntity maid) {
        return maid.getOwnerReference() == null ? null : maid.getOwnerReference().getUUID();
    }

    /** 存档目录优先；旧位置有而新位置无时一次性复制（失败则回退旧路径） */
    private static Path fileFor(SmartMaidEntity maid, UUID owner) {
        Path modern = dirFor(maid).resolve(owner + ".cfg");
        if (Files.exists(modern)) {
            return modern;
        }
        Path legacy = LEGACY_DIR.resolve(owner + ".cfg");
        if (!Files.exists(legacy)) {
            return modern;
        }
        try {
            Files.createDirectories(modern.getParent());
            Files.copy(legacy, modern, StandardCopyOption.COPY_ATTRIBUTES);
            SmartMaid.LOGGER.info("女仆任务配置已迁移到存档目录: {}", modern);
            return modern;
        } catch (Exception e) {
            SmartMaid.LOGGER.warn("女仆任务配置迁移失败，继续使用旧路径: {}", e.toString());
            return legacy;
        }
    }

    /** 存档目录下的女仆数据目录（按存档隔离）；拿不到服务器时回退旧路径 */
    private static Path dirFor(SmartMaidEntity maid) {
        MinecraftServer server = maid.level().getServer();
        if (server == null) {
            return LEGACY_DIR;
        }
        return server.getWorldPath(LevelResource.ROOT).resolve("smartmaid").resolve("maids");
    }
}
