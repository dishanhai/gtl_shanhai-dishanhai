package com.shanhai.backstage;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏 → 后台进程的<b>唯一</b>通信内容（状态文件 {@code config/shanhai/backstage_state.txt}）。
 *
 * <p>一行一个 {@code key=value}，UTF-8；<b>不上 JSON</b>（子进程只需要一个 key 一个 key 地读，
 * 省掉一整层解析器与它的失败模式）。字段固定为下面这些，多出来的 key 一律忽略 ⇒ 旧/新版本混用也不会炸。
 *
 * <h4>标题用的时间字段（2026-11-21 恢复旧格式时新增）</h4>
 * <p>旧标题里的两套计时（{@code 加载时间}/{@code 游玩时间}，格式 {@code a小时b分钟c秒}）原来由主线程
 * 用自己的 {@code nanoTime} 累加器算；现在这些累加器<b>变成状态文件里的绝对时间点 + 累计毫秒</b>，
 * 由子进程用 {@code currentTimeMillis - 时间点} 现算 —— 所以主线程卡多久都不影响读数。
 * <ul>
 *   <li>{@code anchorEpochMs} —— 加载计时的零点（<b>进程启动时刻</b>，与旧版
 *       {@code ManagementFactory.getRuntimeMXBean().getStartTime()} 同一口径）。</li>
 *   <li>{@code pausedAccumMs} —— 「人待在界面里干等」已经累计的毫秒（旧版 {@code menuPausedNanos}）。</li>
 *   <li>{@code pausedSinceEpochMs} —— 当前这一段干等的起点；<b>0 = 此刻不在界面里干等</b>。
 *       还待在界面里时这一段由子进程现减（所以数字会冻结，与旧版一致）。</li>
 *   <li>{@code playedAccumMs} —— 已经结束的存档场次累计的游玩毫秒（旧版 {@code playedAccumNanos}，
 *       退出存档不清零）。</li>
 *   <li>{@code worldEnterEpochMs} —— 当前这一场进入存档的时刻；<b>0 = 不在存档里</b>。</li>
 *   <li>{@code everInWorld} —— 是否进过世界（一旦 true 永不回 false；决定标签是「加载时间」还是「游玩时间」）。</li>
 * </ul>
 *
 * <h4>2026-11-21 新增：{@code activity}（采样器标签）</h4>
 * <p>{@code activity} 是 add 侧的活动采样器从<b>运行时栈</b>取出来的「主线程此刻在干什么」，
 * 由 {@code AddBackstageTitle#publishActivity} 复制上一份状态、只换这一个字段后写出。
 * 子进程把它拼在加载段的转圈后面（{@code 正在加载：X}）；进过世界之后<b>一律不显示</b>。
 * 缺这个键的旧状态文件解析成 {@code ""}（= 没有标签），所以新旧文件可以混用。
 *
 * <p>本类<b>不可变</b>：改状态就新建一个。这样「读到的快照」不会在子进程算到一半时被谁改掉。
 */
public final class BackstageState {

    /** 取不到真值时显示的占位（与 {@code ShanhaiWindowTitle} 里那份同一口径）。 */
    public static final String NA = "不可用";

    /** 还没有窗口句柄。 */
    public static final long NO_HWND = 0L;

    /**
     * {@code activity=} 写进文件的长度上限（48 字符）。
     *
     * <p>状态文件是"一行一个 key"的纯文本，值里再长出换行/超长串只会让读侧更难解析；
     * 上限也和标题栏的可用宽度对得上（标题已经很长了）。
     */
    public static final int ACTIVITY_MAX_CHARS = 48;

    /** 游戏进程号；0 = 未知（探针里会出现）。 */
    public final long pid;
    /** 窗口句柄（HWND，原样存成 long）；{@link #NO_HWND} = 还没取到。 */
    public final long hwnd;
    /** 当前阶段（日志/卡顿判定用；标题文案不再按它切换）。 */
    public final BackstagePhase phase;
    /** 当前阶段起点（epoch 毫秒）；日志用。 */
    public final long startEpochMs;
    /** 加载计时的零点 = 进程启动时刻（epoch 毫秒）。 */
    public final long anchorEpochMs;
    /** 界面干等已累计的毫秒。 */
    public final long pausedAccumMs;
    /** 当前界面干等的起点（epoch 毫秒）；0 = 不在干等。 */
    public final long pausedSinceEpochMs;
    /** 已结束存档场次的游玩累计毫秒。 */
    public final long playedAccumMs;
    /** 当前这一场进入存档的时刻（epoch 毫秒）；0 = 不在存档里。 */
    public final long worldEnterEpochMs;
    /** 是否进过世界（锁存）。 */
    public final boolean everInWorld;
    /** {@code 版本号} 那一段（本 mod jar 的 SHA-256 前 12 位；取不到 = {@link #NA}）。 */
    public final String version;
    /** {@code 构建日期} 那一段（例如 {@code 11月8日}；取不到 = {@link #NA}）。 */
    public final String buildDate;
    /** {@code Welcome:} 那一段（玩家名；取不到 = {@link #NA}）。 */
    public final String welcome;
    /** 写侧序号（诊断用）。 */
    public final long stamp;
    /** add 侧交接过来的<b>最终标题串</b>（空 = 由子进程按时间字段自己拼）。 */
    public final String title;
    /**
     * 采样器给出的「主线程此刻在干什么」标签（{@code ""} = 没有；只在<b>加载段</b>进标题）。
     *
     * <p>🔴 本字段<b>故意不走</b> {@code nn(...)}（那把空串归一成 {@code 不可用}）：空串在这里有语义
     * ——「没有标签」，而 {@code 不可用} 会被拼成一条孤零零的「正在加载：不可用」。
     */
    public final String activity;

    /** 基础构造（探针/旧调用点）：时间字段给 0（= 不在存档、不干等，加载从 epoch 0 算）。 */
    public BackstageState(long pid, long hwnd, BackstagePhase phase, long startEpochMs,
                          String version, String buildDate, String welcome, long stamp) {
        this(pid, hwnd, phase, startEpochMs, startEpochMs, 0L, 0L, 0L, 0L, false,
                version, buildDate, welcome, stamp);
    }

    /** 全量构造。 */
    public BackstageState(long pid, long hwnd, BackstagePhase phase, long startEpochMs,
                          long anchorEpochMs, long pausedAccumMs, long pausedSinceEpochMs,
                          long playedAccumMs, long worldEnterEpochMs, boolean everInWorld,
                          String version, String buildDate, String welcome, long stamp) {
        this(pid, hwnd, phase, startEpochMs, anchorEpochMs, pausedAccumMs, pausedSinceEpochMs,
                playedAccumMs, worldEnterEpochMs, everInWorld, version, buildDate, welcome, stamp, "");
    }

    /** 全量构造 + add 侧交接标题。 */
    public BackstageState(long pid, long hwnd, BackstagePhase phase, long startEpochMs,
                          long anchorEpochMs, long pausedAccumMs, long pausedSinceEpochMs,
                          long playedAccumMs, long worldEnterEpochMs, boolean everInWorld,
                          String version, String buildDate, String welcome, long stamp,
                          String title) {
        this(pid, hwnd, phase, startEpochMs, anchorEpochMs, pausedAccumMs, pausedSinceEpochMs,
                playedAccumMs, worldEnterEpochMs, everInWorld, version, buildDate, welcome, stamp,
                title, "");
    }

    /** 全量构造 + add 侧交接标题 + 采样器活动标签。 */
    public BackstageState(long pid, long hwnd, BackstagePhase phase, long startEpochMs,
                          long anchorEpochMs, long pausedAccumMs, long pausedSinceEpochMs,
                          long playedAccumMs, long worldEnterEpochMs, boolean everInWorld,
                          String version, String buildDate, String welcome, long stamp,
                          String title, String activity) {
        this.pid = pid;
        this.hwnd = hwnd;
        this.phase = phase == null ? BackstagePhase.IDLE : phase;
        this.startEpochMs = Math.max(0L, startEpochMs);
        this.anchorEpochMs = Math.max(0L, anchorEpochMs);
        this.pausedAccumMs = Math.max(0L, pausedAccumMs);
        this.pausedSinceEpochMs = Math.max(0L, pausedSinceEpochMs);
        this.playedAccumMs = Math.max(0L, playedAccumMs);
        this.worldEnterEpochMs = Math.max(0L, worldEnterEpochMs);
        this.everInWorld = everInWorld;
        this.version = nn(version);
        this.buildDate = nn(buildDate);
        this.welcome = nn(welcome);
        this.stamp = Math.max(0L, stamp);
        this.title = nn(title);
        this.activity = activity == null ? "" : activity;
    }

    /** 还没有任何状态时的空值（phase = IDLE，hwnd = 0）。 */
    public static BackstageState empty() {
        return new BackstageState(0L, NO_HWND, BackstagePhase.IDLE, 0L, NA, NA, NA, 0L);
    }

    /** 只换阶段与起点（其余字段原样保留）。 */
    public BackstageState withPhase(BackstagePhase newPhase, long newStartEpochMs) {
        return new BackstageState(pid, hwnd, newPhase, newStartEpochMs, anchorEpochMs,
                pausedAccumMs, pausedSinceEpochMs, playedAccumMs, worldEnterEpochMs, everInWorld,
                version, buildDate, welcome, stamp, title, activity);
    }

    /** 只换句柄。 */
    public BackstageState withHwnd(long newHwnd) {
        return new BackstageState(pid, newHwnd, phase, startEpochMs, anchorEpochMs,
                pausedAccumMs, pausedSinceEpochMs, playedAccumMs, worldEnterEpochMs, everInWorld,
                version, buildDate, welcome, stamp, title, activity);
    }

    /** 只换 add 侧交接的标题。 */
    public BackstageState withTitle(String newTitle) {
        return new BackstageState(pid, hwnd, phase, startEpochMs, anchorEpochMs,
                pausedAccumMs, pausedSinceEpochMs, playedAccumMs, worldEnterEpochMs, everInWorld,
                version, buildDate, welcome, stamp, newTitle, activity);
    }

    /**
     * 只换采样器活动标签（其余字段原样保留）。
     *
     * <p>父侧每写一次 activity 都要新建一份状态：{@link #stamp} 只能在写盘那一刻由
     * {@code BackstageProcess} 自己加，这里不许动它。
     */
    public BackstageState withActivity(String newActivity) {
        return new BackstageState(pid, hwnd, phase, startEpochMs, anchorEpochMs,
                pausedAccumMs, pausedSinceEpochMs, playedAccumMs, worldEnterEpochMs, everInWorld,
                version, buildDate, welcome, stamp, title, newActivity);
    }

    /** 写文件前给序号 +1（只用于诊断；不参与任何决策）。 */
    public BackstageState withStamp(long newStamp) {
        return new BackstageState(pid, hwnd, phase, startEpochMs, anchorEpochMs,
                pausedAccumMs, pausedSinceEpochMs, playedAccumMs, worldEnterEpochMs, everInWorld,
                version, buildDate, welcome, newStamp, title, activity);
    }

    /**
     * 内容是否相同（<b>不看</b> {@link #stamp}）。
     *
     * <h4>🔴 为什么 {@link #activity} <b>必须</b>算进来（与最初设想相反，2026-11-21 实测代码路径后改的）</h4>
     * <p>写盘判据在 {@code BackstageProcess#publish} 里：<b>内容相同就直接 return，一个字节都不写</b>
     * （{@code if (last != null &amp;&amp; state.sameContent(last)) return;}）。而采样器写的状态与
     * {@code lastWritten} 的<b>唯一</b>差别就是 {@code activity} ⇒ 若 activity 不参与比较，
     * <b>标签一变就会被判成"内容相同"而永远写不出去</b>（连第一次都写不出去：父侧是拿
     * {@code lastPublished} 复制一份只换 activity 的，其余字段与 {@code lastWritten} 逐字相同）。
     * <p>那"每次标签变都触发一次写盘"会不会太吵？不会 —— 主线程那一侧
     * （{@code AddBackstageTitle#submitSnapshot}）每次都把<b>上一次的 activity 原样带进新状态</b>，
     * 所以 tick/帧的常规发布与 {@code lastWritten} 内容一致，照样被上面的 return 挡掉；
     * 真正写盘的只有「activity 变了」或「其它字段变了」这两种。父侧另有 500 ms 限流。
     */
    public boolean sameContent(BackstageState other) {
        if (other == null) {
            return false;
        }
        return pid == other.pid
                && hwnd == other.hwnd
                && phase == other.phase
                && startEpochMs == other.startEpochMs
                && anchorEpochMs == other.anchorEpochMs
                && pausedAccumMs == other.pausedAccumMs
                && pausedSinceEpochMs == other.pausedSinceEpochMs
                && playedAccumMs == other.playedAccumMs
                && worldEnterEpochMs == other.worldEnterEpochMs
                && everInWorld == other.everInWorld
                && version.equals(other.version)
                && buildDate.equals(other.buildDate)
                && welcome.equals(other.welcome)
                && title.equals(other.title)
                && activity.equals(other.activity);
    }

    // ─────────────────────────────── 序列化 ───────────────────────────────

    /** 文件内容（每行 {@code key=value}，末尾带换行）。 */
    public String toText() {
        final StringBuilder sb = new StringBuilder(384);
        sb.append("pid=").append(pid).append('\n');
        sb.append("hwnd=0x").append(Long.toHexString(hwnd)).append('\n');
        sb.append("phase=").append(phase.id()).append('\n');
        sb.append("startEpochMs=").append(startEpochMs).append('\n');
        sb.append("anchorEpochMs=").append(anchorEpochMs).append('\n');
        sb.append("pausedAccumMs=").append(pausedAccumMs).append('\n');
        sb.append("pausedSinceEpochMs=").append(pausedSinceEpochMs).append('\n');
        sb.append("playedAccumMs=").append(playedAccumMs).append('\n');
        sb.append("worldEnterEpochMs=").append(worldEnterEpochMs).append('\n');
        sb.append("everInWorld=").append(everInWorld ? 1 : 0).append('\n');
        sb.append("version=").append(oneLine(version)).append('\n');
        sb.append("buildDate=").append(oneLine(buildDate)).append('\n');
        sb.append("welcome=").append(oneLine(welcome)).append('\n');
        sb.append("title=").append(oneLine(title)).append('\n');
        sb.append("activity=").append(oneLineCapped(activity, ACTIVITY_MAX_CHARS)).append('\n');
        sb.append("stamp=").append(stamp).append('\n');
        return sb.toString();
    }

    /**
     * 解析状态文件的行。
     *
     * <p>兼容：空行 / {@code #} 注释行 / 没见过的 key ⇒ 全部跳过。
     * <b>必需</b>字段只有 {@code phase}（其余缺失走默认值）；一个 key 都没有时返回 {@code null}
     * （= 还没写出来 / 读到半个文件），调用方应保留上一份状态。
     */
    public static BackstageState parse(List<String> lines) {
        if (lines == null) {
            return null;
        }
        long pid = 0L;
        long hwnd = NO_HWND;
        BackstagePhase phase = null;
        long startEpochMs = 0L;
        long anchorEpochMs = 0L;
        long pausedAccumMs = 0L;
        long pausedSinceEpochMs = 0L;
        long playedAccumMs = 0L;
        long worldEnterEpochMs = 0L;
        boolean everInWorld = false;
        String version = NA;
        String buildDate = NA;
        String welcome = NA;
        long stamp = 0L;
        String title = "";
        String activity = "";
        int seen = 0;
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            final String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            final String key = line.substring(0, eq).trim();
            final String value = line.substring(eq + 1).trim();
            seen++;
            switch (key) {
                case "pid":
                    pid = parseLong(value, 0L);
                    break;
                case "hwnd":
                    hwnd = parseHwnd(value);
                    break;
                case "phase":
                    phase = BackstagePhase.parse(value);
                    break;
                case "startEpochMs":
                    startEpochMs = parseLong(value, 0L);
                    break;
                case "anchorEpochMs":
                    anchorEpochMs = parseLong(value, 0L);
                    break;
                case "pausedAccumMs":
                    pausedAccumMs = parseLong(value, 0L);
                    break;
                case "pausedSinceEpochMs":
                    pausedSinceEpochMs = parseLong(value, 0L);
                    break;
                case "playedAccumMs":
                    playedAccumMs = parseLong(value, 0L);
                    break;
                case "worldEnterEpochMs":
                    worldEnterEpochMs = parseLong(value, 0L);
                    break;
                case "everInWorld":
                    everInWorld = "1".equals(value) || "true".equalsIgnoreCase(value);
                    break;
                case "version":
                    version = value.isEmpty() ? NA : value;
                    break;
                case "buildDate":
                    buildDate = value.isEmpty() ? NA : value;
                    break;
                case "welcome":
                    welcome = value.isEmpty() ? NA : value;
                    break;
                case "stamp":
                    stamp = parseLong(value, 0L);
                    break;
                case "title":
                    title = value;
                    break;
                case "activity":
                    // 缺这个键 = 老版本父进程写的文件 ⇒ 保持 ""（没有标签），向后兼容
                    activity = value;
                    break;
                default:
                    // 未知 key：旧版本/新版本混用时会出现 —— 跳过，绝不当错误
                    break;
            }
        }
        if (seen == 0 || phase == null) {
            return null;
        }
        // 兼容旧状态文件（没有 anchorEpochMs 的版本）：用 startEpochMs 兜底，别让计时从 1970 年算起
        if (anchorEpochMs <= 0L) {
            anchorEpochMs = startEpochMs;
        }
        return new BackstageState(pid, hwnd, phase, startEpochMs, anchorEpochMs,
                pausedAccumMs, pausedSinceEpochMs, playedAccumMs, worldEnterEpochMs, everInWorld,
                version, buildDate, welcome, stamp, title, activity);
    }

    /** 拆文本行（{@code \r\n} / {@code \n} 都认）。 */
    public static List<String> splitLines(String text) {
        final List<String> out = new ArrayList<>(16);
        if (text == null || text.isEmpty()) {
            return out;
        }
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                out.add(text.substring(start, i));
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    /** 十六进制句柄（{@code 0x...}，也接受纯十进制）。 */
    private static long parseHwnd(String value) {
        if (value == null || value.isEmpty()) {
            return NO_HWND;
        }
        String v = value.trim();
        try {
            if (v.regionMatches(true, 0, "0x", 0, 2)) {
                return Long.parseUnsignedLong(v.substring(2), 16);
            }
            return Long.parseUnsignedLong(v, 10);
        } catch (NumberFormatException ignored) {
            return NO_HWND;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String oneLine(String value) {
        if (value == null) {
            return NA;
        }
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    /** 同 {@link #oneLine(String)}，再按字符数硬截断（{@code maxChars} ≤ 0 = 不截）。 */
    private static String oneLineCapped(String value, int maxChars) {
        final String cleaned = oneLine(value);
        if (maxChars <= 0 || cleaned.length() <= maxChars) {
            return cleaned;
        }
        return cleaned.substring(0, maxChars);
    }

    private static String nn(String value) {
        return (value == null || value.isEmpty()) ? NA : value;
    }

    @Override
    public String toString() {
        return "phase=" + phase + " hwnd=0x" + Long.toHexString(hwnd)
                + " anchor=" + anchorEpochMs + " pausedAccum=" + pausedAccumMs
                + " pausedSince=" + pausedSinceEpochMs + " playedAccum=" + playedAccumMs
                + " worldEnter=" + worldEnterEpochMs + " everInWorld=" + everInWorld
                + " stamp=" + stamp;
    }
}
