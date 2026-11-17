package com.shanhai.common.patternmover;

/**
 * 本包<b>唯一</b>的日志出口 —— 存在的理由是「离线可断言」。
 *
 * <h2>为什么不能直接写 {@code ShanhaiMod.LOGGER}</h2>
 * {@link HostPatternMoverData} / {@link HostPatternMoverEntry} / {@link HostPatternMoverCodec} /
 * {@link HostPatternMoverNbt} 这四个类要在<b>裸 JVM</b> 里跑离线断言（本工程一贯纪律）。
 * 而 {@code com.shanhai.ShanhaiMod} 是 mod 主类：它引 Forge/GTCEu，一被加载就要整个 mod 环境。
 * 一旦这个包里出现 {@code ShanhaiMod.LOGGER.xxx}，那四个类就<b>没法离线跑</b>
 * （真实代价：自证只能靠读代码，而不是跑起来）。
 *
 * <h2>做法</h2>
 * 这里只有一个可替换的 {@link Sink}，默认是 <b>no-op</b>（离线跑：不打日志、也不崩）。
 * 装机时由 {@link HostPatternMoverBootstrap} 换成"转发到 {@code ShanhaiMod.LOGGER}"的实现。
 * ⇒ 生产日志与离线断言共用同一份代码，谁也不迁就谁。
 */
public final class HostPatternMoverLog {

    /** 全包统一的日志前缀（用户要求的可 grep 串）。 */
    public static final String PREFIX = "[SHANHAI-MOVER]";

    /** 日志出口。 */
    public interface Sink {
        void info(String format, Object... args);

        void warn(String format, Object... args);
    }

    private static final Sink NOOP = new Sink() {
        @Override
        public void info(String format, Object... args) {
            // 离线断言：静默（不是"吞异常"，是"没有日志后端"）
        }

        @Override
        public void warn(String format, Object... args) {
            // 同上
        }
    };

    private static volatile Sink sink = NOOP;

    private HostPatternMoverLog() {}

    /** 装后端（幂等；{@code null} ⇒ 恢复 no-op）。 */
    public static void install(Sink newSink) {
        sink = newSink == null ? NOOP : newSink;
    }

    /**
     * 临时换一个出口，并把<b>旧的还给你</b>（自检要数「WARN 打了几行」时用）。
     *
     * <p>🔴 调用方<b>必须</b>在 {@code finally} 里 {@code swap(旧的)} 换回去 ——
     * 否则装机的真日志后端会被这一下打掉（离线断言里无所谓，装机自检里是事故）。
     */
    public static Sink swap(Sink newSink) {
        final Sink prev = sink;
        sink = newSink == null ? NOOP : newSink;
        return prev;
    }

    public static boolean installed() {
        return sink != NOOP;
    }

    public static void info(String format, Object... args) {
        try {
            sink.info(format, args);
        } catch (Throwable ignored) {
            // 日志后端炸了不许影响搬运
        }
    }

    public static void warn(String format, Object... args) {
        try {
            sink.warn(format, args);
        } catch (Throwable ignored) {
            // 同上
        }
    }
}
