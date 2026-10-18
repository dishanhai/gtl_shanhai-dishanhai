package com.shanhai.add.time;

import java.util.Locale;

/**
 * 山海重构 · 世界时钟的三种模式（三态互斥）。
 *
 * <h2>为什么是这三个，而不是一个 {@code rate}</h2>
 * <ul>
 *   <li>{@link #LIVE} —— 正常流动；{@code rate} 生效（本轮 rate 恒 1，加速是下一轮的活）。</li>
 *   <li>{@link #PAUSED} —— 世界停住；{@code rate} <b>不生效</b>
 *       （见 {@link ShanhaiWorldClock#effectiveRate()}：返回 0，避免"倍率 0"这种要额外判空的状态）。</li>
 *   <li>{@link #REPLAY} —— <b>本轮不实现</b>，只为「时间倒流」占位。
 *       🔴 倒流<b>不是</b> {@code rate < 0}：tick 不可逆，倒流只能走「日志回放 + 播放头」，
 *       所以它必须是与 {@code rate} 正交的第三个状态，而不是倍率的一个取值。
 *       （论证见 {@code temp\time-census\报告.md}「统一入口建议」：用负倍率表达倒流会立刻导致
 *       "负倍率怎么跑 tick"这种无解问题。）</li>
 * </ul>
 *
 * <p>本枚举刻意<b>零 Minecraft 依赖</b>：它和 {@link ShanhaiWorldClock} 一起被
 * {@code tools\clock-selfcheck} 的离屏自检用裸 {@code javac} 编译运行，
 * 不碰 Forge/游戏类路径（红线：本轮不许构建/启动游戏）。
 */
public enum ShanhaiClockMode {

    /** 世界正常流动。 */
    LIVE,
    /** 世界冻结（本轮唯一实现的非 LIVE 状态）。 */
    PAUSED,
    /** 倒流回放（占位，本轮不实现）。 */
    REPLAY;

    /**
     * 按名字（大小写不敏感、允许前后空白）解析；不认识就返回 {@code null}。
     *
     * <p><b>为什么返回 null 而不是回退到 LIVE</b>：命令路径要「拒绝并提示」、
     * 网络路径要「回退到 LIVE」，两种处理不一样，所以这里只做解析、把策略留给调用方
     * （{@link ShanhaiWorldClock#applyModeName} / {@link ShanhaiWorldClock#modeOrFallback}）。
     *
     * @param raw 原始字符串，可为 {@code null}
     * @return 解析出的模式，或 {@code null} 表示非法
     */
    public static ShanhaiClockMode byName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        for (ShanhaiClockMode m : values()) {
            if (m.name().equals(s)) {
                return m;
            }
        }
        // 允许中文/别名口径，方便用户在游戏里少打字（不认识的一律 null ⇒ 拒绝）
        switch (s) {
            case "暂停":
            case "停":
            case "PAUSE":
            case "STOP":
                return PAUSED;
            case "继续":
            case "恢复":
            case "RESUME":
            case "PLAY":
            case "RUN":
                return LIVE;
            case "倒流":
            case "回放":
            case "REWIND":
            case "REVERSE":
                return REPLAY;
            default:
                return null;
        }
    }
}
