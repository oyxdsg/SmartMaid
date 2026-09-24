package com.oyxdsg.smartmaid.entity.ai;

import com.oyxdsg.smartmaid.SmartMaid;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

/**
 * 女仆移动调试日志输出（开发模式默认开启）。
 *
 * <p>日志统一加 {@code [SmartMaid-Debug]} 前缀，供外部监测脚本（tools/maid_monitor.ps1）抓取。</p>
 */
public final class MaidDebug {
    private static final Logger LOGGER = SmartMaid.LOGGER;
    public static final String PREFIX = "[SmartMaid-Debug] ";

    /** 开发模式：始终开启调试输出；发布正式版时改为 false 即可整体关闭 */
    private static final boolean ENABLED = true;

    /**
     * 高噪日志开关（地形俯视图等）：默认关闭。
     * 排查移动/寻路问题时改 true，平时保持 false 以免淹没关键日志。
     */
    private static final boolean VERBOSE = false;

    private MaidDebug() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static boolean verbose() {
        return ENABLED && VERBOSE;
    }

    public static void log(String message) {
        if (ENABLED) {
            LOGGER.info(PREFIX + message);
        }
    }

    public static String fmt1(double v) {
        return String.format("%.1f", v);
    }

    /** 物品名（空物品返回 empty） */
    public static String itemName(ItemStack stack) {
        return stack.isEmpty() ? "empty" : stack.getItem().getDescriptionId();
    }
}
