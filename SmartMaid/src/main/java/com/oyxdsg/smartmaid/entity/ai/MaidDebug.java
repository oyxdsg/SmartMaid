package com.oyxdsg.smartmaid.entity.ai;

import com.oyxdsg.smartmaid.SmartMaid;
import com.oyxdsg.smartmaid.data.SmartMaidConfig;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

/**
 * 女仆调试日志输出。
 *
 * <p>日志统一加 {@code [SmartMaid-Debug]} 前缀，供外部监测脚本（tools/maid_monitor.ps1）抓取。</p>
 *
 * <p><b>开关改由配置控制（2026-09-29）</b>：读 {@code config/smartmaid/main.json} 的
 * {@code "debug"} / {@code "debugVerbose"} —— <b>不用重新编译</b>，改完重启游戏即可生效。
 * 两者默认都是 {@code false}（发布版保持玩家日志干净）。</p>
 * <ul>
 *   <li>{@code debug: true} —— 打开事件级日志（任务启停、挖掘、破坏、放置、移动、跳跃等）；</li>
 *   <li>{@code debugVerbose: true} —— 同时打开高频日志（地形俯视图、渲染帧级等），需 {@code debug} 也为 true。</li>
 * </ul>
 *
 * <p>与 AutoTest 的分工：AutoTest 的结果走 {@code SmartMaid.LOGGER}（永远输出，不受本开关影响）——
 * 测试结论不能依赖可关闭的调试开关，否则"跑过了"和"没跑"在日志上无法区分。</p>
 */
public final class MaidDebug {
    private static final Logger LOGGER = SmartMaid.LOGGER;
    public static final String PREFIX = "[SmartMaid-Debug] ";

    private static volatile boolean enabled = false;
    private static volatile boolean verbose = false;
    private static volatile boolean loaded = false;

    private MaidDebug() {
    }

    /**
     * 从配置读取开关（幂等）。
     *
     * <p>由 {@code SmartMaid.onInitialize} 预热，避免运行期反复读配置；
     * 若没预热到，首次调用 {@link #enabled()} 时也会自动加载。</p>
     */
    public static void reload() {
        enabled = SmartMaidConfig.debug();
        verbose = SmartMaidConfig.debugVerbose();
        loaded = true;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            reload();
        }
    }

    public static boolean enabled() {
        ensureLoaded();
        return enabled;
    }

    public static boolean verbose() {
        ensureLoaded();
        return enabled && verbose;
    }

    public static void log(String message) {
        if (enabled()) {
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
