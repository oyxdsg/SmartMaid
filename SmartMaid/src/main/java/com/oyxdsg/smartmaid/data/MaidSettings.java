package com.oyxdsg.smartmaid.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.entity.SmartMaidEntity;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 女仆设置（与玩家 UUID 绑定，存 config/smartmaid/settings/&lt;玩家UUID&gt;.json）。
 *
 * <p>设置由游戏内女仆菜单修改：客户端只发「字段 + 档位索引」，服务端权威修改并落盘，
 * 同时通过 {@link SmartMaidEntity#getSettings()} 让行为即时生效。</p>
 *
 * <p>数值类设置统一用「档位索引」存储，方便用原版按钮循环切换，也避免客户端直接传任意值。</p>
 */
public final class MaidSettings {

    /** 护主模式：主动（遇怪就上）/ 被动（只反击打主人的怪）/ 关闭 */
    public enum ProtectMode { ACTIVE, PASSIVE, OFF }

    /** 死亡掉落：全部掉落 / 保留装备（重召唤恢复）/ 完全不掉落 */
    public enum DeathDropMode { ALL, KEEP_EQUIPMENT, NONE }

    public static final float[] FOLLOW_START_STEPS = {2.0F, 3.0F, 5.0F, 8.0F, 12.0F};
    public static final float[] FOLLOW_STOP_STEPS = {6.0F, 10.0F, 15.0F, 24.0F, 40.0F};
    public static final double[] MAX_HEALTH_STEPS = {10.0D, 20.0D, 24.0D, 40.0D, 60.0D, 100.0D};
    public static final float[] RATE_STEPS = {0.5F, 1.0F, 1.5F, 2.0F, 3.0F};

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<UUID, MaidSettings> CACHE = new HashMap<>();
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("smartmaid").resolve("settings");

    private boolean friendlyFire;
    private boolean followEnabled = true;
    private int followStartIndex = 1;
    private int followStopIndex = 1;
    private ProtectMode protectMode = ProtectMode.ACTIVE;
    private DeathDropMode deathDropMode = DeathDropMode.ALL;
    private int maxHealthIndex = 2;
    private int hungerRateIndex = 1;
    private int regenRateIndex = 1;

    public boolean isFriendlyFire() {
        return this.friendlyFire;
    }

    public boolean isFollowEnabled() {
        return this.followEnabled;
    }

    public int getFollowStartIndex() {
        return this.followStartIndex;
    }

    public int getFollowStopIndex() {
        return this.followStopIndex;
    }

    public ProtectMode getProtectMode() {
        return this.protectMode;
    }

    public DeathDropMode getDeathDropMode() {
        return this.deathDropMode;
    }

    public int getMaxHealthIndex() {
        return this.maxHealthIndex;
    }

    public int getHungerRateIndex() {
        return this.hungerRateIndex;
    }

    public int getRegenRateIndex() {
        return this.regenRateIndex;
    }

    /**
     * 按「字段 + 档位索引」修改设置（服务端调用，字段来自客户端网络包）。
     * 未知字段忽略。
     */
    public void applyField(String field, int value) {
        switch (field) {
            case "friendlyFire" -> this.friendlyFire = value != 0;
            case "followEnabled" -> this.followEnabled = value != 0;
            case "followStart" -> this.followStartIndex = clampIndex(value, FOLLOW_START_STEPS.length);
            case "followStop" -> this.followStopIndex = clampIndex(value, FOLLOW_STOP_STEPS.length);
            case "protectMode" -> this.protectMode = ProtectMode.values()[clampIndex(value, ProtectMode.values().length)];
            case "deathDrop" -> this.deathDropMode = DeathDropMode.values()[clampIndex(value, DeathDropMode.values().length)];
            case "maxHealth" -> this.maxHealthIndex = clampIndex(value, MAX_HEALTH_STEPS.length);
            case "hungerRate" -> this.hungerRateIndex = clampIndex(value, RATE_STEPS.length);
            case "regenRate" -> this.regenRateIndex = clampIndex(value, RATE_STEPS.length);
            default -> {
            }
        }
    }

    /** 把设置应用到女仆实体（最大血量 + 饱食消耗/回血速率）。 */
    public void applyToMaid(SmartMaidEntity maid) {
        var maxHealth = maid.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            double max = MAX_HEALTH_STEPS[clampIndex(this.maxHealthIndex, MAX_HEALTH_STEPS.length)];
            maxHealth.setBaseValue(max);
            if (maid.getHealth() > max) {
                maid.setHealth((float) max);
            }
        }
        maid.getMaidFood().setRates(
                RATE_STEPS[clampIndex(this.hungerRateIndex, RATE_STEPS.length)],
                RATE_STEPS[clampIndex(this.regenRateIndex, RATE_STEPS.length)]);
    }

    /** 读取玩家设置（带缓存；不存在则返回默认设置）。 */
    public static MaidSettings get(UUID owner) {
        if (owner == null) {
            return new MaidSettings();
        }
        MaidSettings cached = CACHE.get(owner);
        if (cached != null) {
            return cached;
        }
        MaidSettings loaded = read(owner);
        CACHE.put(owner, loaded);
        return loaded;
    }

    /** 把缓存中的设置写盘。 */
    public static void save(UUID owner) {
        if (owner == null) {
            return;
        }
        MaidSettings settings = CACHE.get(owner);
        if (settings == null) {
            return;
        }
        try {
            Files.createDirectories(DIR);
            try (Writer writer = Files.newBufferedWriter(fileFor(owner))) {
                GSON.toJson(settings, writer);
            }
        } catch (IOException e) {
            SmartMaid.LOGGER.error("保存女仆设置失败: {}", owner, e);
        }
    }

    private static MaidSettings read(UUID owner) {
        Path file = fileFor(owner);
        if (!Files.exists(file)) {
            return new MaidSettings();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            MaidSettings settings = GSON.fromJson(reader, MaidSettings.class);
            return settings == null ? new MaidSettings() : settings;
        } catch (Exception e) {
            SmartMaid.LOGGER.error("读取女仆设置失败: {}", owner, e);
            return new MaidSettings();
        }
    }

    private static Path fileFor(UUID owner) {
        return DIR.resolve(owner + ".json");
    }

    private static int clampIndex(int value, int length) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, length - 1);
    }
}
