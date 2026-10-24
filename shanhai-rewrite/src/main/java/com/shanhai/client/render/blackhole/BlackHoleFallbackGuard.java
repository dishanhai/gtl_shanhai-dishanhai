package com.shanhai.client.render.blackhole;

import java.util.function.Consumer;

/**
 * 山海重构 · 黑洞渲染件的 <b>三级兜底状态机</b>（纯逻辑，零 MC / 零 GL）。
 *
 * <h2>任务的硬要求（逐条对上）</h2>
 * <ol>
 *   <li>一级 = 完整引力透镜（屏幕空间采样 + 程序化吸积盘 / 光子环）；</li>
 *   <li>二级 = 一级的 shader 编译 / 加载 / 运行任何一步失败 ⇒ 退成「极简贴图 quad 版」；</li>
 *   <li>三级 = 二级也失败 ⇒ 什么都不画；</li>
 *   <li>每次降级<b>必须打一行</b> {@code [SHANHAI-BH] fallback=<级别> reason=<原因>}，
 *       并且<b>必须可被日志断言</b>。</li>
 * </ol>
 *
 * <h2>「可被日志断言」是怎么保证的（三条设计）</h2>
 * <ol>
 *   <li><b>格式是常量拼出来的</b>：{@link #fallbackLine(int, String)} 是唯一出口，
 *       行首、{@code fallback=}、{@code reason=} 之间的空格都是字面量，测试直接比字符串。</li>
 *   <li><b>reason 不含空白</b>：{@link #sanitize(String)} 把空白和 {@code =} 换成 {@code _}
 *       ⇒ 一条日志恰好一行、<b>能被 {@code split(" ")} 切成固定列</b>，不会因为异常消息里
 *       带空格而把断言打散。</li>
 *   <li><b>同一级别只打一次</b>（{@code degradeTo} 里 {@code newLevel <= level} 直接返回 false）：
 *       降级是<b>一次性事件</b>，不是每帧刷屏；否则日志里每帧一行会把"到底降过没有"淹掉。</li>
 * </ol>
 *
 * <h2>为什么只降不升</h2>
 * 一次会话内<b>不自动升级</b>：shader 编译失败通常是驱动 / 资源包 / 加载顺序问题，
 * 每帧重试会变成"每帧编译一次"（最坏情况帧率归零，比降级还糟）。
 * 需要重试的场合由调用方显式调 {@link #reset()}（本件只在资源重载时用）。
 *
 * <p>⚠️ <b>{@link #reset()} 不清 {@code loggedForLevel}</b>：一次会话里同一级别只报一次，
 * 这样"日志里出现几行 fallback"永远等于"真的降过几次级"。
 */
public final class BlackHoleFallbackGuard {

    /** 日志前缀（与任务书逐字一致；改这里等于改日志契约）。 */
    public static final String PREFIX = "[SHANHAI-BH]";

    /** 一级：完整透镜。 */
    public static final int LEVEL_FULL = 1;
    /** 二级：极简贴图 quad（程序化黑盘 + 光环）。 */
    public static final int LEVEL_QUAD = 2;
    /** 三级：什么都不画。 */
    public static final int LEVEL_NONE = 3;

    private static final String REASON_NONE = "none";
    private static final String REASON_UNKNOWN = "unknown";
    private static final String REASON_RESTORED = "restored";

    private final Consumer<String> sink;

    private int level = LEVEL_FULL;
    private String reason = REASON_NONE;
    private int loggedForLevel;

    public BlackHoleFallbackGuard(Consumer<String> sink) {
        this.sink = sink;
    }

    public int level() {
        return level;
    }

    public String reason() {
        return reason;
    }

    /** 三级：这一帧<b>什么都不该画</b>。 */
    public boolean isDead() {
        return level >= LEVEL_NONE;
    }

    /** 已经不是一级了（= 正在走兜底）。 */
    public boolean isDegraded() {
        return level > LEVEL_FULL;
    }

    /** 上一次打出去的降级行（测试/诊断用；没降过级则是 {@code null}）。 */
    public String lastLine() {
        return loggedForLevel == 0 ? null : fallbackLine(loggedForLevel, reason);
    }

    /**
     * 降级。只接受<b>更差</b>的级别；同级/更好的一律忽略（并返回 {@code false}）。
     *
     * @param newLevel {@link #LEVEL_QUAD} 或 {@link #LEVEL_NONE}
     * @param why      原因（任意字符串；会被 {@link #sanitize} 压成无空白标识符）
     * @return 是否真的发生了降级（= 是否打了一行）
     */
    public boolean degradeTo(int newLevel, String why) {
        if (newLevel <= this.level) {
            return false;
        }
        this.level = Math.min(newLevel, LEVEL_NONE);
        this.reason = sanitize(why);
        emit(this.level, this.reason);
        return true;
    }

    /**
     * <b>不改变级别</b>地打一行降级日志（给「环境原因」用：例如装了光影包时一级不可用，
     * 但那个开关随时可能被关掉，不该把它钉死成会话级降级）。
     *
     * <p>仍然满足任务书的「必须打一行」：只打一次，reason 可被断言。
     */
    public void logOnce(int levelForLine, String why) {
        final int lv = Math.max(LEVEL_FULL + 1, Math.min(levelForLine, LEVEL_NONE));
        if (loggedForLevel >= lv) {
            return;
        }
        emit(lv, sanitize(why));
    }

    /** 环境原因解除：打一行「恢复」，并把「已报过」的级别清回去（下一次降级还能再报一次）。 */
    public void logRestored() {
        if (loggedForLevel == 0) {
            return;
        }
        loggedForLevel = 0;
        sink.accept(PREFIX + " restore level=" + level + " reason=" + REASON_RESTORED);
    }

    /** 资源重载后允许重试一级（只对<b>还没死</b>的局面有效：三级不复活，避免闪来闪去）。 */
    public void reset() {
        if (level >= LEVEL_NONE) {
            return;
        }
        level = LEVEL_FULL;
        reason = REASON_NONE;
    }

    private void emit(int lv, String why) {
        if (lv <= loggedForLevel) {
            return;                       // 同一级别一次会话只报一次（"几行 fallback" = "真降过几次级"）
        }
        loggedForLevel = lv;
        sink.accept(fallbackLine(lv, why));
    }

    /** 唯一的降级行格式（测试逐字比对这条）。 */
    public static String fallbackLine(int level, String reason) {
        return PREFIX + " fallback=" + level + " reason=" + reason;
    }

    /**
     * 把任意原因压成 <b>无空白、无 {@code =}、无控制字符</b>的标识符。
     * 空 / null ⇒ {@code unknown}。
     */
    public static String sanitize(String why) {
        if (why == null) {
            return REASON_UNKNOWN;
        }
        final String s = why.trim();
        if (s.isEmpty()) {
            return REASON_UNKNOWN;
        }
        final String out = s.replaceAll("[\\s=]+", "_").replaceAll("[^\\x21-\\x7e]", "_");
        return out.isEmpty() ? REASON_UNKNOWN : out;
    }
}
