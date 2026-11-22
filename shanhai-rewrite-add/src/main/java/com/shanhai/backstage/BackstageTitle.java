package com.shanhai.backstage;

/**
 * 标题文本的<b>唯一</b>拼法：游戏侧（窗口出生那一帧 / {@code setScreen} 时的 {@code createTitle}）
 * 与子进程（每 100 ms）必须得到<b>同一个格式</b>。
 *
 * <h4>🔴 格式 = 旧 jar 的 {@code ShanhaiWindowTitle}（2026-11-21 用户要求「给我改回去」）</h4>
 * <p>规格不是凭记忆，是从旧 jar（{@code temp/build-20261122-fix5/shanhai-0.1.0.jar}）的
 * {@code com/shanhai/client/ShanhaiWindowTitle.class} 常量池逐字导出来的：
 * <pre>
 * 常量池实测：
 *   len=43 [GregTech Leisure:Shanhai Re-release version]
 *   len=10 [  Welcome:]          ← 2 个空格
 *   len=8  [    版本号:]          ← 4 个空格
 *   len=8  [   构建日期:]          ← 3 个空格
 *   len=3  [   ]                 ← 3 个空格（计时标签前）
 *   len=4  [加载时间] / [游玩时间]
 *   len=8  [\u0001小时\u0001分钟\u0001秒]  ← a小时b分钟c秒（无空格、无前导零）
 *   len=7  [\u0001 \u0001tick]           ← " " + tick数 + "tick"
 *   len=3  [[|]] [[/]] [[-]] [[\]]        ← 转圈 4 帧（带方括号）
 * </pre>
 * 于是两行完整形态是：
 * <pre>
 * GregTech Leisure:Shanhai Re-release version  Welcome:名    版本号:哈希   构建日期:11月8日   加载时间:0小时12分钟3秒 [|]
 * GregTech Leisure:Shanhai Re-release version  Welcome:名    版本号:哈希   构建日期:11月8日   游玩时间:0小时3分钟12秒 15tick
 * </pre>
 * <ul>
 *   <li>加载中：{@code 加载时间:} + {@code a小时b分钟c秒} + 一个空格 + 转子帧（{@code [|] / [/] / [-] / [\]}，
 *       <b>200 ms 一帧</b>，与旧版 {@code LOADING_FRAME_NANOS = 200_000_000} 同周期）；
 *       人待在界面里干等时转子停住（旧版 {@code menuEnterNanos != 0 ⇒ 空串}）。</li>
 *   <li>进过世界：{@code 游玩时间:} + {@code a小时b分钟c秒} + 一个空格 + {@code Ntick}
 *       （{@code N = 毫秒/50 % 20}，与旧版 {@code playedText} 一致），不再有转子。</li>
 * </ul>
 *
 * <h4>🔴 唯一新增的一段：{@code   正在加载：<标签>}（2026-11-21 用户要求）</h4>
 * <p>用户原话：「加载时间的那个转圈后面再加一条：<b>正在加载：钩子的内容</b>」。
 * 于是加载段（{@code !s.everInWorld}）在转圈动画之后追加 <b>3 个空格</b> + {@code 正在加载：} +
 * {@code s.activity}（标签来自运行时栈，见 {@code ActivitySampler}）。除此以外本类一个字没改。
 * <p><b>判据（两条，缺一不可）</b>：
 * <ol>
 *   <li>{@code everInWorld = true} 之后<b>永远不出现</b>「正在加载」—— 进世界后是
 *       {@code 游玩时间:… Ntick}，多一个字都不行；</li>
 *   <li>{@code activity} 为空时<b>不出现</b>孤零零的「正在加载：」（宁可没有这一段，
 *       也不能出现一个没有内容的标签）。</li>
 * </ol>
 * <p>注意 {@code everInWorld=false} 里还含着"人待在界面里干等"（{@code pausedSinceEpochMs > 0}，
 * 那时<b>没有转圈</b>）：按上面第 1 条的口径，这一段照样会出现在时长后面（这是"只加在加载段"的
 * 直接结果，不是新加的判断）。</p>
 *
 * <h4>数据来源换了，文案一个字符都没换</h4>
 * <p>旧版这些数字由主线程用 {@code System.nanoTime()} 现算；现在由子进程用状态文件里的
 * 「anchorEpochMs + pausedAccumMs + pausedSinceEpochMs + playedAccumMs + worldEnterEpochMs」现算
 * （见 {@link BackstageState}），所以主线程卡住时这些数字照样在走。格式化函数
 * {@link #durationText(long)} / {@link #playedText(long)} 与旧版逐字对应。
 */
public final class BackstageTitle {

    /** 标题固定前缀（旧 jar 常量池第一批：43 字）。 */
    public static final String HEAD = "GregTech Leisure:Shanhai Re-release version";

    /** 旧版转圈帧（带方括号，4 帧，200 ms 一帧）。 */
    public static final String[] LOADING_FRAMES = {"[|]", "[/]", "[-]", "[\\]"};

    /** 旧版换帧周期：200 ms。 */
    public static final long LOADING_FRAME_MS = 200L;

    /**
     * 加载段那一段的固定前缀（{@code 正在加载：}，全角冒号）。
     *
     * <p>只有 {@code !everInWorld} 且 {@code activity} 非空时才会被拼上（见类注释的判据）。
     */
    public static final String ACTIVITY_PREFIX = "正在加载：";

    private BackstageTitle() {
    }

    /**
     * 拼一次标题（旧格式）。
     *
     * @param state       状态快照
     * @param nowMs       现在（epoch 毫秒）
     * @param hungCreditMs 子进程侧的「卡顿补偿毫秒」（卡住且当时在界面里干等时，把这段补进加载时间；
     *                     游戏侧传 0）
     */
    public static String compose(BackstageState state, long nowMs, long hungCreditMs) {
        final BackstageState s = state == null ? BackstageState.empty() : state;
        final StringBuilder sb = new StringBuilder(192);
        sb.append(HEAD);
        sb.append("  Welcome:").append(s.welcome);
        sb.append("    版本号:").append(s.version);
        sb.append("   构建日期:").append(s.buildDate);
        sb.append("   ");
        sb.append(s.everInWorld ? "游玩时间" : "加载时间").append(':');
        sb.append(timerText(s, nowMs, hungCreditMs));
        final String animation = animation(s, nowMs);
        if (!animation.isEmpty()) {
            sb.append(' ').append(animation);   // 旧版：动画与时长之间只有 1 个空格
        }
        // 🔴 唯一新增的一段（2026-11-21 用户要求「加载时间的那个转圈后面再加一条」）：
        //    转圈之后 3 个空格 + 「正在加载：」+ 采样器标签。
        if (!s.everInWorld && !s.activity.isEmpty()) {
            sb.append("   ").append(ACTIVITY_PREFIX).append(s.activity);
        }
        return sb.toString();
    }

    /** 计时那一段：{@code a小时b分钟c秒}（加载）或 {@code a小时b分钟c秒 Ntick}（游玩）。 */
    public static String timerText(BackstageState s, long nowMs, long hungCreditMs) {
        if (!s.everInWorld) {
            return durationText(loadingElapsedMs(s, nowMs, hungCreditMs));
        }
        return playedText(playedElapsedMs(s, nowMs));
    }

    /**
     * 加载已累计多久（毫秒）：{@code now - anchor - paused - 当前干等 + 卡顿补偿}，下限 0。
     *
     * <p>与旧版 {@code loadingElapsedNanos} 同一语义：anchor = 进程启动，
     * 减去「人待在界面里干等」的累计与当前这一段；卡顿补偿是子进程加的（旧版没有，因为旧版卡住时根本没人算）。
     */
    public static long loadingElapsedMs(BackstageState s, long nowMs, long hungCreditMs) {
        long paused = s.pausedAccumMs;
        if (s.pausedSinceEpochMs > 0L) {
            paused += Math.max(0L, nowMs - s.pausedSinceEpochMs);
        }
        final long elapsed = nowMs - s.anchorEpochMs - paused + Math.max(0L, hungCreditMs);
        return Math.max(0L, elapsed);
    }

    /** 游玩已累计多久（毫秒）：已结束场次 + 本场；退出存档不清零。 */
    public static long playedElapsedMs(BackstageState s, long nowMs) {
        long played = s.playedAccumMs;
        if (s.worldEnterEpochMs > 0L) {
            played += Math.max(0L, nowMs - s.worldEnterEpochMs);
        }
        return Math.max(0L, played);
    }

    /**
     * 转圈帧：旧版规则 —— <b>进过世界 或 正在界面里干等 ⇒ 空串</b>；否则
     * {@code frames[(now / 200ms) % 4]}。
     */
    public static String animation(BackstageState s, long nowMs) {
        if (s.everInWorld || s.pausedSinceEpochMs > 0L) {
            return "";
        }
        final int frame = (int) Math.floorMod(nowMs / LOADING_FRAME_MS, (long) LOADING_FRAMES.length);
        return LOADING_FRAMES[frame];
    }

    /**
     * 时长文本：{@code a小时b分钟c秒}（三段都显示、不补零）—— 与旧 jar 常量池
     * {@code [\u0001小时\u0001分钟\u0001秒]} 逐字对应。
     */
    public static String durationText(long elapsedMs) {
        final long millis = Math.max(0L, elapsedMs);
        final long totalSeconds = millis / 1_000L;
        final long hours = totalSeconds / 3_600L;
        final long minutes = (totalSeconds / 60L) % 60L;
        final long seconds = totalSeconds % 60L;
        return hours + "小时" + minutes + "分钟" + seconds + "秒";
    }

    /**
     * 游玩时长文本：{@code a小时b分钟c秒 Ntick} —— 与旧 jar 常量池
     * {@code [\u0001 \u0001tick]} 逐字对应（{@code N = 毫秒/50 % 20}）。
     */
    public static String playedText(long elapsedMs) {
        final long millis = Math.max(0L, elapsedMs);
        final long ticks = (millis / 50L) % 20L;
        return durationText(millis) + " " + ticks + "tick";
    }

    // ─────────────────────── 浮层用的短文本（不是标题） ───────────────────────

    /**
     * 浮层上那行短文本：{@code 加载 12.3s} / {@code 加载 12.3s（卡住 3.1s）}。
     *
     * <p>🔴 这是<b>新增的浮层</b>，不是标题；标题仍是上面的旧格式。浮层要在卡住时也能肉眼跳，
     * 所以用 0.1 s 精度。
     */
    public static String overlayText(BackstageState s, long loadingMs, long stallMs) {
        final StringBuilder sb = new StringBuilder(48);
        sb.append("加载 ").append(tenths(loadingMs / 1000.0D)).append('s');
        if (stallMs > 0L) {
            sb.append("（卡住 ").append(tenths(stallMs / 1000.0D)).append("s）");
        }
        return sb.toString();
    }

    /** 秒数文本：一位小数（{@code 12.3}），不查 Locale。 */
    public static String tenths(double secs) {
        if (secs <= 0.0D || Double.isNaN(secs) || Double.isInfinite(secs)) {
            return "0.0";
        }
        final long t = (long) Math.floor(secs * 10.0D);
        return (t / 10L) + "." + (t % 10L);
    }

    /** 日志里的 {@code secs=12.3}。 */
    public static String logSeconds(double secs) {
        return tenths(secs);
    }
}
