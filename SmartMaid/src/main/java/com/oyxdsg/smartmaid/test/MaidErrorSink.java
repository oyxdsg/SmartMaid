package com.oyxdsg.smartmaid.test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 统一的**错误接收通道**：把「我们主动上报的失败」和「未捕获异常」写到
 * {@code <gameDir>/smartmaid/errors.jsonl}（JSON Lines，一行一条，append-only）。
 *
 * <p>为什么要它：整合包环境里出问题时，唯一能拿到的是 {@code latest.log}，而它是 GBK、
 * 混着几千行别人的日志、且异常经常被 mod 框架吞掉或包一层。这个通道提供
 * <b>机器可读、只含本模组信息、可跨会话累积</b> 的载体 —— 配合
 * {@code tools/read_test_report.py} 一条命令就能看全。</p>
 *
 * <p>写入策略：**绝不抛异常、绝不阻塞**。任何 IO 失败只打一条 WARN 就放弃，
 * 避免"上报本身把游戏搞崩"。文件超大（> 2 MiB）时自动轮转成 {@code errors.1.jsonl}。</p>
 */
public final class MaidErrorSink {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    /** 日志器：由 SmartMaid 主类注入，避免本包依赖主类造成环。 */
    private static volatile java.util.function.BiConsumer<String, Throwable> fallbackLog;

    private static Path file;
    private static boolean installed;
    private static int count;

    private MaidErrorSink() {
    }

    /** 由主类注册一个「实在写不进去时的兜底日志器」。 */
    public static void setFallbackLogger(java.util.function.BiConsumer<String, Throwable> logger) {
        fallbackLog = logger;
    }

    public static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    /** {@code <gameDir>/smartmaid/errors.jsonl} */
    public static synchronized Path errorsFile() {
        if (file == null) {
            file = gameDir().resolve("smartmaid").resolve("errors.jsonl");
        }
        return file;
    }

    /** 已写入的条数（供测试/命令查询通道是否工作）。 */
    public static synchronized int written() {
        return count;
    }

    /**
     * 安装全局未捕获异常处理器（幂等）。
     *
     * <p>会**链式调用**原有处理器，不改变崩溃行为 —— 只多一条记录。</p>
     *
     * @param role 进程角色，仅用于会话头（如 {@code client} / {@code server}）
     */
    public static synchronized void install(String role) {
        note("session", role + " 错误通道就绪 (mc=" + mcVersion() + ")");
        if (installed) {
            return;
        }
        installed = true;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            report("uncaught@" + thread.getName(), "未捕获异常", error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    public static String mcVersion() {
        try {
            return FabricLoader.getInstance().getModContainer("minecraft")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        } catch (Throwable ignored) {
            return "?";
        }
    }

    public static String modVersion() {
        try {
            return FabricLoader.getInstance().getModContainer("smartmaid")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 记一条信息级记录（不是错误，但需要留痕，如"测试开始""通道就绪"）。 */
    public static void note(String tag, String message) {
        write("INFO", tag, message, null);
    }

    /** 记一条错误（主动上报，例如某个功能失败了但被我们捕获）。 */
    public static void report(String tag, String message, Throwable error) {
        write("ERROR", tag, message, error);
    }

    public static void report(String tag, Throwable error) {
        write("ERROR", tag, String.valueOf(error), error);
    }

    /** 记一条警告（可疑但不算失败）。 */
    public static void warn(String tag, String message) {
        write("WARN", tag, message, null);
    }

    private static synchronized void write(String level, String tag, String message, Throwable error) {
        try {
            Path f = errorsFile();
            Files.createDirectories(f.getParent());
            rotateIfNeeded(f);
            StringBuilder sb = new StringBuilder(512);
            sb.append("{\"ts\":\"").append(esc(LocalDateTime.now().format(TS))).append('"')
                    .append(",\"level\":\"").append(level).append('"')
                    .append(",\"tag\":\"").append(esc(tag)).append('"')
                    .append(",\"mc\":\"").append(esc(mcVersion())).append('"')
                    .append(",\"mod\":\"").append(esc(modVersion())).append('"')
                    .append(",\"thread\":\"").append(esc(Thread.currentThread().getName())).append('"')
                    .append(",\"msg\":\"").append(esc(message)).append('"');
            if (error != null) {
                StringWriter sw = new StringWriter();
                error.printStackTrace(new PrintWriter(sw));
                sb.append(",\"exception\":\"").append(esc(error.getClass().getName())).append('"')
                        .append(",\"stack\":\"").append(esc(sw.toString())).append('"');
            }
            sb.append("}\n");
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            count++;
        } catch (Throwable t) {
            java.util.function.BiConsumer<String, Throwable> fb = fallbackLog;
            if (fb != null) {
                fb.accept("[MaidErrorSink] 上报失败: " + tag + " / " + message, t);
            }
        }
    }

    private static void rotateIfNeeded(Path f) throws IOException {
        if (Files.exists(f) && Files.size(f) > MAX_BYTES) {
            Path old = f.resolveSibling("errors.1.jsonl");
            Files.deleteIfExists(old);
            Files.move(f, old);
        }
    }

    /** 最小 JSON 字符串转义（只处理必需字符，避免引入 gson 依赖）。 */
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
