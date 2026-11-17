package com.shanhai.add.guard.stack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.shanhai.add.hotfix.HotfixPatchWhitelist;

/**
 * 山海 · <b>「违规现场」的完整调用栈</b>（<b>纯 JDK</b>：只碰 {@code java.*} ＋ 同一个白名单类）。
 *
 * <h2>🔴 抓栈的位置被用户当场纠正过一次，这一条不能回退</h2>
 * <pre>
 *   「主动拆代码谁叫你只抓伤害事件相关的了，你去抓所有代码」
 *   「昨天我说的那个主动检测并废掉敌方的代码，你到底能不能做」
 * </pre>
 * 2026-11-08 那一版把抓栈挂在<b>轮询点</b>（tick 末比对发现"值被改了"的那一刻）。那天 46 条
 * {@code field=motion} 的 {@code source} <b>全是 unknown</b>、{@code strike_caller} 打 {@code none} /
 * {@code frames=0}。原因不是抓取代码坏了，而是<b>站错了地方</b>：
 * <pre>
 *   轮询点上的栈 = ServerTickEvent → 我们的类 → …（<b>全部是我们自己的帧</b>）
 *   敌方帧早在它写完、返回之后就不在栈上了
 * </pre>
 * ⇒ 现在只在<b>原版被写的那一刻</b>抓（注入点 HEAD）：
 * <table border="1">
 *   <tr><th>{@code where=}</th><th>注入点（原版方法）</th><th>能看到的敌方帧</th></tr>
 *   <tr><td>{@code health}</td><td>{@code LivingEntity#setHealth(float)}</td>
 *       <td>直写血量的调用者（例如 {@code examplemod.util.EntityUtil#forceHurt}）</td></tr>
 *   <tr><td>{@code die}</td><td>{@code ServerPlayer#die} / {@code LivingEntity#die}</td>
 *       <td>谁在判死</td></tr>
 *   <tr><td>{@code remove}</td><td>{@code Entity#setRemoved}</td><td>谁在摘玩家</td></tr>
 *   <tr><td>{@code motion}</td><td>{@code Entity#setDeltaMovement(Vec3)}</td>
 *       <td>{@code push/knockback} 的调用者 —— <b>但直接 {@code putfield} 的那条路看不到</b>（见下）</td></tr>
 *   <tr><td>{@code pos}</td><td>{@code Entity#setPosRaw(DDD)}（{@code final}，{@code setPos/absMoveTo/teleportTo} 都过它）</td>
 *       <td>谁在挪玩家</td></tr>
 *   <tr><td>{@code attack}</td><td>伤害事件被取消的那一拍（{@code LivingAttackEvent}/{@code LivingHurtEvent}）</td>
 *       <td>伤害源</td></tr>
 * </table>
 *
 * <h2>🔴 诚实边界：哪些写入<b>抓不到栈</b>（写死在这里，不许含糊）</h2>
 * <ul>
 *   <li><b>直接写字段</b>（{@code putfield} / 反射 / 别人的 {@code @Shadow}）绕得开上面<b>每一个</b>方法。
 *       本工程 {@code temp\momentum\报告.md} 附录 A 就有实例：{@code examplemod.util.EntityUtil#forceSetDeltaMovement}
 *       在偏移 {@code 0–5} 直接 {@code putfield f_19827_}（= {@code Entity.deltaMovement}），
 *       <b>一次 {@code setDeltaMovement} 都不调</b>。这种写入：
 *       ① <b>拦得住</b>（我们判的是"值变了没有"，与谁写的无关）；
 *       ② <b>抓不到栈</b>（没有方法调用，就没有帧）。日志会如实写 {@code first_external=none} /
 *          {@code frames=0}，<b>不编一个名字</b>。</li>
 *   <li>别的线程上的写入（异步任务）不会出现在这段栈里 —— 我们只抓<b>当前线程</b>的栈。</li>
 * </ul>
 *
 * <h2>日志形态（用户点名要的这一行）</h2>
 * <pre>
 *   [SHANHAI-GUARD] violation_stack where=motion target=Steve \
 *        first_external=examplemod.util.EntityUtil#forceKnockback:39 frames=9 \
 *        top=com.shanhai.add.mixin.ShanhaiValueWriteProbeMixin#shanhai$onMotion:41|net.minecraft.…|examplemod.…|…
 * </pre>
 * <b>{@code top=} 永远是<b>不过滤</b>的前 16 帧</b>（用户原话：「你去抓所有代码…先不做白名单过滤」）；
 * {@code first_external=} 用的是<b>与 auto_counter 完全相同的那一份白名单</b>
 * （{@link HotfixPatchWhitelist#isNeverPatch}，也就是 {@code HotfixTargetResolver#resolve} 内部用的那一个判据）。
 * <p>三个字段的三种组合，含义<b>不许混淆</b>：
 * <table border="1">
 *   <tr><th>形态</th><th>含义</th></tr>
 *   <tr><td>{@code frames=0 first_external=none}</td><td>确实一帧都没有（栈抓取本身失败／无帧）</td></tr>
 *   <tr><td>{@code frames=9 first_external=none filtered_all=true}</td>
 *       <td>有帧，但<b>全被白名单挡掉了</b>（原版帧 / {@code java.*} / {@code com.shanhai.*}）⇒ 显式标出来</td></tr>
 *   <tr><td>{@code frames=9 first_external=x.y#m:12}</td><td>抓到外部帧（这一帧进 strike 通道，可当热改目标）</td></tr>
 * </table>
 *
 * <h2>限流（用户口径 1/s，慢路径）</h2>
 * 同一个 {@code where|target} <b>最多每 {@link #MIN_INTERVAL_MILLIS} 毫秒抓一次栈</b>；
 * 被挡下的次数累计（{@link #throttled()}），并按"下一次真正抓到时折进日志"的口径不做假读。
 * 为什么限流：{@code StackWalker} 一次是几十微秒级，而 {@code setHealth}/{@code setPosRaw} 是被调得
 * 最狠的两个方法；被猫连推的那几秒里，每个 tick 都可能命中，不挡就会拖慢 tick。
 */
public final class ShanhaiViolationStack {

    /** 与本体 {@code ShanhaiGuardCore.LOG_PREFIX} / {@code ShanhaiStateLog.PREFIX} 逐字相同。 */
    public static final String PREFIX = "[SHANHAI-GUARD] ";

    /** 这一族日志的统一词头。 */
    public static final String TITLE = "violation_stack";

    /** 日志里最多列几帧（{@code top=} 那一段）。 */
    public static final int MAX_FRAMES = 16;

    /** 抓栈时最多扫多少帧（先扫这么多，再剔掉本机制的帧，取前 {@link #MAX_FRAMES}）。 */
    public static final int MAX_SCAN_FRAMES = 48;

    /** 同一个 {@code where|target} 最快多久抓一次（毫秒）。 */
    public static final long MIN_INTERVAL_MILLIS = 1000L;

    /** 限流表上限（防泄漏：{@code where} 只有 6 种、玩家数远小于 64）。 */
    public static final int MAX_SHAPES = 128;

    /**
     * "抓栈机制自己"的类名前缀 —— 这些帧<b>不算</b>候选外部帧（它们永远存在、永远没用）。
     *
     * <p>⚠️ 这是<b>唯一</b>的过滤：除了这四类，其余帧（包括原版帧、{@code com.shanhai.*} 我们的帧）
     * <b>一律</b>都进 {@code top=}，这样"全是自己人的帧"这件事是<b>看得见</b>的（{@code filtered_all=true}）。
     */
    private static final String[] MACHINERY_PREFIXES = {
            "com.shanhai.add.guard.stack.ShanhaiViolationStack",
            "java.lang.StackWalker",
            "java.lang.Thread",
            "java.lang.Throwable",
    };

    /** 收到一次违规现场时的回调（游戏侧接到 {@code HotfixTriageTap}；离线自检接 stdout）。 */
    public interface Sink {

        /**
         * @param hit 这一次抓到的现场（帧表已经就绪）
         */
        void onViolation(Hit hit);
    }

    /** 一次抓栈的结果（不可变）。 */
    public static final class Hit {

        /** {@code health|die|remove|motion|pos|attack}。 */
        public final String where;

        /** 玩家名（拿不到 ⇒ {@code ?}）。 */
        public final String target;

        /** 不过滤的帧表（≤ {@link #MAX_FRAMES}）。 */
        public final List<String> frames;

        /** 第一个非白名单帧（{@code 类#方法:行}）；没有 ⇒ {@code none}。 */
        public final String firstExternal;

        /** 有帧、但全被白名单挡掉了（此时 {@code first_external=none} 是"过滤掉的"而不是"没有帧"）。 */
        public final boolean filteredAll;

        /** 这一行落地的墙钟毫秒（strike 保鲜用）。 */
        public final long atMillis;

        Hit(String where, String target, List<String> frames, String firstExternal,
            boolean filteredAll, long atMillis) {
            this.where = where == null ? "?" : where;
            this.target = target == null || target.isEmpty() ? "?" : target;
            this.frames = frames == null ? new ArrayList<>(0) : frames;
            this.firstExternal = firstExternal == null ? "none" : firstExternal;
            this.filteredAll = filteredAll;
            this.atMillis = atMillis;
        }

        /** 帧数。 */
        public int frameCount() {
            return frames.size();
        }
    }

    private static volatile Sink sink;

    /** {@code where|target} ⇒ 上次抓栈的时刻。 */
    private static final Map<String, Long> LAST = new LinkedHashMap<>();

    private static final AtomicLong CAPTURED = new AtomicLong();
    private static final AtomicLong THROTTLED = new AtomicLong();
    private static final AtomicLong REPORTED = new AtomicLong();
    private static final AtomicLong FED = new AtomicLong();
    private static final AtomicLong EMPTY = new AtomicLong();
    private static final AtomicLong FILTERED_ALL = new AtomicLong();

    private ShanhaiViolationStack() {}

    // ═══════════════════════════════ 装配 ═══════════════════════════════

    /** 接上"把现场交给热改通道"的出口（游戏侧在装配点调；{@code null} = 只打日志）。 */
    public static void bind(Sink newSink) {
        sink = newSink;
    }

    /** 清空限流记忆（命令 / 离线自检用）。 */
    public static synchronized void resetRateLimit() {
        LAST.clear();
    }

    /** 现在有没有接上出口（读数）。 */
    public static boolean wired() {
        return sink != null;
    }

    // ═══════════════════════════════ 读数 ═══════════════════════════════

    /** 真抓到过多少次栈。 */
    public static long captured() {
        return CAPTURED.get();
    }

    /** 被 1/s 限流挡掉多少次。 */
    public static long throttled() {
        return THROTTLED.get();
    }

    /** 打出去多少行。 */
    public static long reported() {
        return REPORTED.get();
    }

    /** 喂给 strike 通道多少次。 */
    public static long fed() {
        return FED.get();
    }

    /** 抓到"一帧都没有"多少次（= 栈真的空）。 */
    public static long empty() {
        return EMPTY.get();
    }

    /** 抓到"有帧但全被白名单挡掉"多少次。 */
    public static long filteredAll() {
        return FILTERED_ALL.get();
    }

    /** 一行读数。 */
    public static String statusLine() {
        return "violation_stack captured=" + CAPTURED.get()
                + " throttled=" + THROTTLED.get()
                + " reported=" + REPORTED.get()
                + " fed_strike=" + FED.get()
                + " empty=" + EMPTY.get()
                + " filtered_all=" + FILTERED_ALL.get()
                + " wired=" + (sink != null)
                + " min_interval_ms=" + MIN_INTERVAL_MILLIS
                + " max_frames=" + MAX_FRAMES;
    }

    // ═══════════════════════════════ 抓 ═══════════════════════════════

    /**
     * 抓一次现场（含 1/s 限流）。
     *
     * @param where  {@code health|die|remove|motion|pos|attack}
     * @param target 玩家名（可以 {@code null}）
     * @return 抓到了 ⇒ 结果；被限流挡下／抓取失败 ⇒ {@code null}
     */
    public static Hit capture(String where, String target) {
        if (!allow(where, target)) {
            return null;
        }
        final List<StackTraceElement> raw;
        try {
            // StackWalker 只走一趟（用户口径：「StackWalker 只走一次，取前 ~16 帧」）。
            // ⚠️ 它给的是 StackWalker.StackFrame，不是 StackTraceElement ⇒ 必须转一次。
            raw = StackWalker.getInstance().walk(
                    s -> s.limit(MAX_SCAN_FRAMES)
                            .map(StackWalker.StackFrame::toStackTraceElement)
                            .collect(Collectors.toList()));
        } catch (Throwable t) {
            CAPTURED.incrementAndGet();
            EMPTY.incrementAndGet();
            return new Hit(where, target, new ArrayList<>(0), "none", false, System.currentTimeMillis());
        }
        return build(where, target, raw, System.currentTimeMillis());
    }

    /**
     * 用一份现成的栈抓（离线自检、以及"栈已经由注入点自己取好"的场合）。
     *
     * <p>与 {@link #capture(String, String)} <b>同一套判定</b>（同一份白名单、同一种形态），
     * 区别只是帧从参数来。
     */
    public static Hit captureFrom(String where, String target, StackTraceElement[] stack) {
        final List<StackTraceElement> list = new ArrayList<>();
        if (stack != null) {
            for (int i = 0; i < stack.length && list.size() < MAX_SCAN_FRAMES; i++) {
                if (stack[i] != null) {
                    list.add(stack[i]);
                }
            }
        }
        return build(where, target, list, System.currentTimeMillis());
    }

    /** 组装一次结果（纯函数：不取栈、不限流、不打日志）。 */
    public static Hit build(String where, String target, List<StackTraceElement> stack, long atMillis) {
        final List<String> frames = new ArrayList<>(MAX_FRAMES);
        if (stack != null) {
            for (int i = 0; i < stack.size() && frames.size() < MAX_FRAMES; i++) {
                final StackTraceElement e = stack.get(i);
                if (e == null || machinery(e.getClassName())) {
                    continue;
                }
                frames.add(frameText(e));
            }
        }
        final String first = firstExternal(frames);
        final boolean filteredAll = "none".equals(first) && !frames.isEmpty();
        CAPTURED.incrementAndGet();
        if (frames.isEmpty()) {
            EMPTY.incrementAndGet();
        } else if (filteredAll) {
            FILTERED_ALL.incrementAndGet();
        }
        return new Hit(where, target, frames, first, filteredAll, atMillis);
    }

    // ═══════════════════════════════ 用现成的 Hit 做事（打日志 ＋ 喂通道） ═══════════════════════════════

    /**
     * 把现场喂给 strike 通道（{@link Sink}）。
     *
     * <p>本类<b>不自己打日志</b>：日志出口由调用方（{@code ShanhaiViolationProbe}）接
     * {@code ShanhaiMod.LOGGER}，本类保持纯 JDK，离线自检才能跑同一份字节码。
     *
     * @param hit {@link #capture} / {@link #captureFrom} 的结果（{@code null} = 什么都没做）
     * @return {@code true} = 通道真的被喂了（{@code false} = 没接上出口／hit 为空）
     */
    public static boolean feed(Hit hit) {
        if (hit == null) {
            return false;
        }
        REPORTED.incrementAndGet();
        final Sink target = sink;
        if (target == null) {
            return false;
        }
        FED.incrementAndGet();
        try {
            target.onViolation(hit);
        } catch (Throwable ignored) {
            // 上层（热改通道）炸了也不许把 tick 带崩
        }
        return true;
    }

    /**
     * 组一行（<b>纯函数</b>，不限流、不打日志）。
     *
     * @param hit 现场
     * @return 完整一行：{@code [SHANHAI-GUARD] violation_stack where=… target=… first_external=… frames=… top=…}
     */
    public static String line(Hit hit) {
        final StringBuilder sb = new StringBuilder(512);
        sb.append(PREFIX).append(TITLE)
                .append(" where=").append(token(hit.where))
                .append(" target=").append(token(hit.target))
                .append(" first_external=").append(token(hit.firstExternal))
                .append(" frames=").append(hit.frameCount())
                .append(" top=").append(topText(hit.frames));
        if (hit.frameCount() == 0) {
            sb.append(" empty=true");
        } else if (hit.filteredAll) {
            sb.append(" filtered_all=true");
        }
        return sb.toString();
    }

    /** {@code top=} 那一段：帧之间用 ASCII 竖线（与本体 {@code strike_caller} 的帧分隔同族）。 */
    public static String topText(List<String> frames) {
        if (frames == null || frames.isEmpty()) {
            return "none";
        }
        final StringBuilder sb = new StringBuilder(256);
        for (int i = 0; i < frames.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(frames.get(i));
        }
        return sb.toString();
    }

    /**
     * 第一个"外部"帧（<b>与 auto_counter 同一份白名单</b>）。
     *
     * @param frames 帧表（{@code 类#方法:行}）
     * @return {@code 类#方法:行}；一帧可用的都没有 ⇒ {@code none}
     */
    public static String firstExternal(List<String> frames) {
        if (frames == null || frames.isEmpty()) {
            return "none";
        }
        for (int i = 0; i < frames.size(); i++) {
            final String cls = classNameOf(frames.get(i));
            if (cls == null || cls.isEmpty()) {
                continue;
            }
            if (HotfixPatchWhitelist.isNeverPatch(cls)) {
                continue;
            }
            return frames.get(i);
        }
        return "none";
    }

    /** 一帧的规范写法：{@code 类#方法:行}（行号未知 ⇒ {@code :?}）。 */
    public static String frameText(StackTraceElement e) {
        if (e == null) {
            return "?";
        }
        final String cls = e.getClassName() == null ? "?" : e.getClassName();
        final String method = e.getMethodName() == null ? "?" : e.getMethodName();
        final int line = e.getLineNumber();
        return cls + "#" + method + ":" + (line >= 0 ? String.valueOf(line) : "?");
    }

    /** 这一帧是不是"抓栈机制自己"的帧。 */
    public static boolean machinery(String className) {
        if (className == null) {
            return true;
        }
        for (int i = 0; i < MACHINERY_PREFIXES.length; i++) {
            if (className.startsWith(MACHINERY_PREFIXES[i])) {
                return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════ 内部 ═══════════════════════════════

    /** 1/s 限流（{@code where|target} 一个形态）。 */
    private static boolean allow(String where, String target) {
        final String shape = token(where) + '|' + token(target);
        final long now = System.currentTimeMillis();
        synchronized (ShanhaiViolationStack.class) {
            final Long last = LAST.get(shape);
            if (last != null && now - last < MIN_INTERVAL_MILLIS) {
                THROTTLED.incrementAndGet();
                return false;
            }
            LAST.put(shape, Long.valueOf(now));
            if (LAST.size() > MAX_SHAPES) {
                final String oldest = LAST.keySet().iterator().next();
                LAST.remove(oldest);
            }
        }
        return true;
    }

    private static String classNameOf(String frame) {
        if (frame == null) {
            return null;
        }
        final int hash = frame.indexOf('#');
        return hash <= 0 ? null : frame.substring(0, hash);
    }

    private static String token(String text) {
        if (text == null || text.isEmpty()) {
            return "?";
        }
        final StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            sb.append(Character.isWhitespace(c) || c == '|' ? '_' : c);
        }
        return sb.toString();
    }
}
