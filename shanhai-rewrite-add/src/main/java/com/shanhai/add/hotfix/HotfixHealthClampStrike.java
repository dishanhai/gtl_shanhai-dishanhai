package com.shanhai.add.hotfix;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 🔴 <b>写侧钳制命中 ⇒ 一条 strike</b>（2026-11-21 P2）：{@code health_clamp} 那一拍抓一次栈，
 * 直接喂给 {@link HotfixTriageTap#submitViolation}（{@code where=health}、
 * {@code source=health_clamp}）。
 *
 * <h2>为什么必须有这一条（现场读数）</h2>
 * <pre>
 *   现场：health_clamp 命中 284 次（{@code SynchedEntityData#assignValues} 那一条腿）
 *        而 where=health 的违规源 <b>0 条</b>
 *   ⇒ 夹住之后<b>没有喂给溯源</b> ⇒ 整条链的"看见对手"这一步从来没发生过
 *   ⇒ 于是帧分类 verdict=foreign=0、chain_wipe_handoff=0、keyclass_restore=0
 * </pre>
 *
 * <h2>什么时候调（调用方负责，这里只做最后一道）</h2>
 * <pre>
 *   ① 受保护玩家（判据与写侧钳制逐字同一份）；
 *   ② 这次要写进去的值<b>会读成死的</b>（{@code <=0 / NaN / ±∞}）；
 *   ③ 在<b>同一个拍</b>（钳制命中那一刻的调用链上）调一次。
 * </pre>
 * <p>这里再守一次 ①②（{@code guarded=false} 或值正常 ⇒ <b>一条都不喂</b>），
 * 因为"喂错"比"少喂"贵：一条伪造的 strike 会把溯源引到无辜的类上。
 *
 * <h2>限流（两层，缺一不可）</h2>
 * <pre>
 *   同 tick（{@value #TICK_MILLIS} ms）只喂一条         ← 一拍里连命中 5 次也只留 1 条
 *   同一类（被写的那个实体类）{@value #PER_CLASS_INTERVAL_TICKS} tick 内最多再喂一条
 *   ⇒ 现场 284 次命中 ⇒ 最多 284/… ≈ 每 10 秒 1 条，而不是每秒 20 条把 tap 冲垮
 * </pre>
 * <p>⚠️ "同一类"取的是<b>被写的那个实体的类</b>（{@code player.getClass().getName()}），
 * <b>不是</b>栈上谁在写 —— {@code assignValues} 那条腿的栈里根本没有对手的帧
 * （写值的人不在这个进程的调用链上）。
 *
 * <h2>日志（逐字）</h2>
 * <pre>
 *   health_clamp_strike value=-Infinity field_before=20.000 frames=16 fed=true
 * </pre>
 * <p>{@code fed=} 是 {@link HotfixTriageTap#submitViolation} 的<b>真实返回值</b>
 * （{@code false} = tap 没装上/上层炸了 ⇒ 如实写 false，不假装喂出去了）。
 *
 * <h2>依赖面</h2>
 * {@code java.*} ＋ 同包 {@link HotfixTriageTap}（纯 JDK）⇒ 离线自检跑的是<b>同一份</b>字节码。
 */
public final class HotfixHealthClampStrike {

    /** 日志行首（逐字）。 */
    public static final String LINE = "health_clamp_strike";

    /** 进 {@code strike_source=} 的来源名（与 {@code health_clamp} 日志同一个词）。 */
    public static final String SOURCE = HotfixTriageTap.Strike.SOURCE_HEALTH_CLAMP;

    /** strike 的类别（四类规范名之一）。 */
    public static final String WHERE = "health";

    /** 1 tick 折算的毫秒数（与 {@link HotfixWriteCooldown#TICK_MILLIS} 同一个口径）。 */
    public static final long TICK_MILLIS = 50L;

    /** 同一类两次喂 strike 之间的最小间隔（tick）。 */
    public static final long PER_CLASS_INTERVAL_TICKS = 200L;

    /** 一次 strike 最多带几帧（与 {@link HotfixTriageTap#VIOLATION_MAX_FRAMES} 同口径）。 */
    public static final int MAX_FRAMES = HotfixTriageTap.VIOLATION_MAX_FRAMES;

    /** 限流状态（全部在同一把锁里改）。 */
    private static final Object LOCK = new Object();

    /** 实体类名 ⇒ 上一次喂 strike 的 tick。 */
    private static final Map<String, Long> LAST_TICK_BY_CLASS = new HashMap<String, Long>();

    /** 上一次真的喂出去的那一拍（同一拍只喂一条）。 */
    private static long lastFedTick = Long.MIN_VALUE;

    /** 通过全部闸门（受保护 ＋ 死值）的命中次数（只进读数）。 */
    private static long hits;

    /** 真的喂进 tap 的次数（只进读数）。 */
    private static long fedTotal;

    private HotfixHealthClampStrike() {
        // 纯静态
    }

    /**
     * 写侧钳制命中那一刻调（<b>同一拍</b>）。
     *
     * @param guarded     这个实体是受保护玩家（调用方已算好，与写侧钳制同一份判据）
     * @param value       这一次要写进去的值（{@code set} 腿）／已经被灌进字段的值（{@code assignValues} 腿）
     * @param fieldBefore 写入前的字段真值（读不到 ⇒ {@link Float#NaN}；日志里写 {@code NaN}）
     * @param targetClass 被写的那个实体的类（限流键；{@code null} ⇒ 用 {@code ?}）
     * @param player      玩家名（可 {@code null}；只用于 tap 的配对）
     * @return {@code true} = 真的喂进 tap 了
     */
    public static boolean onClampHit(boolean guarded, float value, float fieldBefore,
                                     Class<?> targetClass, String player) {
        return onClampHit(System.currentTimeMillis(), guarded, value, fieldBefore, targetClass, player);
    }

    /**
     * 与 {@link #onClampHit(boolean, float, float, Class, String)} 同一个动作，
     * 但<b>时间由调用方给</b>（离线自证用它把"同一拍 / 200 tick"跑成确定读数）。
     *
     * @param atMillis 这一拍的墙钟毫秒
     */
    static boolean onClampHit(long atMillis, boolean guarded, float value, float fieldBefore,
                              Class<?> targetClass, String player) {
        try {
            // ---- ① 最后一道闸门（调用方已经判过一遍，这里绝不放宽）
            if (!guarded) {
                return false;                       // 未受保护 ⇒ 一条都不喂（不许阻断正常死亡/正常写血）
            }
            if (!deadValue(value)) {
                return false;                       // 正常值 ⇒ 这不是违规现场
            }
            final String cn = targetClass == null ? "?" : targetClass.getName();
            final long tick = atMillis / TICK_MILLIS;
            synchronized (LOCK) {
                hits++;
                if (lastFedTick != Long.MIN_VALUE && tick == lastFedTick) {
                    return false;                   // 同一拍只喂一条
                }
                final Long prev = LAST_TICK_BY_CLASS.get(cn);
                if (prev != null && tick >= prev.longValue()
                        && tick - prev.longValue() < PER_CLASS_INTERVAL_TICKS) {
                    return false;                   // 同一类 200 tick 内最多再喂一条
                }
                lastFedTick = tick;
                LAST_TICK_BY_CLASS.put(cn, Long.valueOf(tick));
            }
            // ---- ② 同一拍抓栈（≤16 帧）＋ 立刻喂进既有 strike 通道
            final List<String> frames = capture(MAX_FRAMES);
            boolean fed = false;
            try {
                fed = HotfixTriageTap.submitViolation(WHERE, player, frames, atMillis, SOURCE);
            } catch (Throwable ignored) {
                fed = false;                        // 通道炸了也只影响这一条读数
            }
            synchronized (LOCK) {
                if (fed) {
                    fedTotal++;
                }
            }
            log(LINE + " value=" + num(value)
                    + " field_before=" + num(fieldBefore)
                    + " frames=" + frames.size()
                    + " fed=" + fed);
            return fed;
        } catch (Throwable ignored) {
            return false;                           // 判据坏了 ⇒ 什么都不做（绝不把写血带崩）
        }
    }

    /** 通过闸门的命中次数（读数；离线自检用）。 */
    public static long hits() {
        synchronized (LOCK) {
            return hits;
        }
    }

    /** 真的喂进 tap 的次数（读数；离线自检用）。 */
    public static long fedCount() {
        synchronized (LOCK) {
            return fedTotal;
        }
    }

    /** 清空限流状态与读数（离线自检用；游戏里没有调用点）。 */
    public static void reset() {
        synchronized (LOCK) {
            LAST_TICK_BY_CLASS.clear();
            lastFedTick = Long.MIN_VALUE;
            hits = 0L;
            fedTotal = 0L;
        }
    }

    // ═══════════════════════════════ 内部小工具 ═══════════════════════════════

    /**
     * "这个数会读成死的"：与 {@code ShanhaiGuardValueLayer#needsHealthClamp} <b>同一条判据</b>
     * （{@code !(v > 0)} 兜住 NaN，{@code isInfinite} 兜住 ±∞）。
     */
    private static boolean deadValue(float v) {
        return !(v > 0.0F) || Float.isInfinite(v);
    }

    /**
     * 抓栈：<b>不过滤</b>的前 {@code max} 帧，只跳过本类的帧（否则头几行永远是
     * {@code capture/onClampHit}，把真正的调用者挤出去）。
     */
    private static List<String> capture(int max) {
        final List<String> out = new ArrayList<String>(max);
        try {
            final StackTraceElement[] frames = new Throwable().getStackTrace();
            for (int i = 0; frames != null && i < frames.length && out.size() < max; i++) {
                final StackTraceElement e = frames[i];
                if (e == null) {
                    continue;
                }
                if (HotfixHealthClampStrike.class.getName().equals(e.getClassName())) {
                    continue;                       // 本类的壳不算"谁写的"
                }
                out.add(e.getClassName() + "#" + e.getMethodName() + ":" + e.getLineNumber());
            }
        } catch (Throwable ignored) {
            // 抓栈坏了只意味着这一条少几帧
        }
        return out;
    }

    /**
     * 数值文本（与 {@code HotfixReadClassRestore#num} 同一个口径）：
     * {@code NaN} ／ {@code -Infinity} ／ 三位小数（{@code 20.000}）。
     */
    private static String num(float v) {
        if (Float.isNaN(v)) {
            return "NaN";
        }
        if (Float.isInfinite(v)) {
            return v > 0.0F ? "Infinity" : "-Infinity";
        }
        return String.format(Locale.ROOT, "%.3f", Float.valueOf(v));
    }

    /** 日志出口：统一走热改引擎那一行（{@code HotfixAutoCounter.chainLog}）。 */
    private static void log(String line) {
        try {
            HotfixAutoCounter.chainLog(line);
        } catch (Throwable ignored) {
            // 日志坏了不许影响写血
        }
    }
}
