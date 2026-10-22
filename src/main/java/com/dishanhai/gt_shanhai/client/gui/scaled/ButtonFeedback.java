package com.dishanhai.gt_shanhai.client.gui.scaled;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 按钮即时反馈的状态机，不依赖 Minecraft；界面只按 {@link #visual} 给出的参数去画。
 *
 * <p>三件事各管各的：</p>
 * <ul>
 *   <li>按下：每个 pressKey 一根欠阻尼弹簧，按下压到 1，松开弹回时越过 0 一点，做出回弹。
 *   同一时刻只有一个「正按着」的按钮；拖出按钮会弹回、拖回再压下，松开前都算按住。</li>
 *   <li>连发：挂在正按着的按钮上，节奏由 {@link RepeatCurve} 现算；游标在外面时暂停计时，
 *   回来接着原节奏，不补发。</li>
 *   <li>动作：每个 actionKey 记去抖、等待回执（requestId）与结果闪色；等太久按超时收尾，不会一直转。</li>
 * </ul>
 *
 * <p>时间一律用调用方传入的毫秒（{@code System.currentTimeMillis()}），与 {@link Spring} 相同。</p>
 */
public final class ButtonFeedback {

    public enum Tone { SUCCESS, PARTIAL, FAILED, NEUTRAL }

    public enum Phase { IDLE, PENDING, FLASH }

    /** 连发回调；返回 false 停止本次连发（比如数量已到边界）。{@code repeatIndex} 从 1 起。 */
    @FunctionalInterface
    public interface RepeatAction {
        boolean fire(int repeatIndex);
    }

    /**
     * 一帧的画法参数。{@code press} 是弹簧原值（回弹时略小于 0）；{@code scale}、{@code shakeX} 已按「减少动效」处理。
     * 没在闪时 {@code tone=NEUTRAL}、{@code flashMix=0}。
     */
    public record Visual(float press, float scale, float shakeX, Tone tone, float flashMix,
                         boolean pending, float pendingPhase) {}

    /** 按下：欠阻尼，松开时越过 0 约一成再回来。 */
    public static final Spring.Preset PRESS = new Spring.Preset(0.12, 0.58, 0.002f, 0.03f);

    public static final long FLASH_MS = 800L;
    public static final long SHAKE_MS = 320L;
    public static final long NUDGE_MS = 180L;
    public static final long PENDING_TIMEOUT_MS = 5_000L;
    public static final long DEBOUNCE_MS = 300L;
    public static final long PENDING_PERIOD_MS = 900L;
    /** 一帧最多补发的连发次数；卡顿积压的其余次数直接丢掉。 */
    public static final int MAX_REPEAT_BURST = 3;

    private static final float SHAKE_PX = 2.5f;
    private static final float NUDGE_PX = 1.5f;
    private static final long FLASH_RISE_MS = 60L;
    private static final long FLASH_HOLD_END_MS = 260L;
    private static final long ACTION_FORGET_MS = 2_000L;
    private static final int SHADE_COLOR = 0xFF101010;

    private static final class ActionState {
        Phase phase = Phase.IDLE;
        int requestId;
        long pendingAtMs;
        long pendingTimeoutMs = PENDING_TIMEOUT_MS;
        Tone tone = Tone.NEUTRAL;
        long flashAtMs;
        long lastBeginMs = Long.MIN_VALUE / 2;
    }

    private final SpringTable<Long> hoverTable;
    private final SpringTable<Long> pressSprings = new SpringTable<>(PRESS);
    private final Map<Long, ActionState> actions = new HashMap<>();
    private final Map<Integer, Long> pendingIds = new HashMap<>();
    private final Map<Long, Long> nudgeAtMs = new HashMap<>();

    // 正按着的按钮
    private boolean holding;
    private long holdKey;
    private int holdX, holdY, holdW, holdH;
    private boolean holdInside;
    private long holdStartMs;
    private long pausedTotalMs;
    private long pausedAtMs;
    private RepeatCurve repeatCurve;
    private RepeatAction repeatAction;
    private int repeatFired;

    public ButtonFeedback() {
        this(new SpringTable<>(Spring.HOVER));
    }

    /** 共用调用方已有的 hover 表（同一个 {@link Spring#HOVER}），两边观感一致。 */
    public ButtonFeedback(SpringTable<Long> hoverTable) {
        this.hoverTable = hoverTable;
    }

    public float hover(long hoverKey, boolean hovered, long nowMs) {
        return hoverTable.follow(hoverKey, hovered ? 1f : 0f, nowMs);
    }

    // ============ 按下 / 松开 / 连发 ============

    /** 按下 (x,y,w,h) 这个按钮。不可用时只轻抖一下，不压下，返回 false。之前按着的按钮先松开。 */
    public boolean press(long pressKey, int x, int y, int w, int h, boolean enabled, long nowMs) {
        if (!enabled) {
            nudge(pressKey, nowMs);
            return false;
        }
        if (holding) release(nowMs);
        holding = true;
        holdKey = pressKey;
        holdX = x;
        holdY = y;
        holdW = w;
        holdH = h;
        holdInside = true;
        holdStartMs = nowMs;
        pausedTotalMs = 0L;
        repeatCurve = null;
        repeatAction = null;
        repeatFired = 0;
        pressSprings.get(pressKey, nowMs).setTarget(1f, nowMs);
        return true;
    }

    /** 给正按着的按钮挂上连发；没按着时忽略。按下那一次由调用方自己执行，这里只管之后的。 */
    public void holdRepeat(RepeatCurve curve, RepeatAction action, long nowMs) {
        if (!holding || curve == null || action == null) return;
        repeatCurve = curve;
        repeatAction = action;
        repeatFired = 0;
        holdStartMs = nowMs;
        pausedTotalMs = 0L;
    }

    /** 松开：弹簧弹回 0（欠阻尼会越过一点），连发停止。返回刚才是否真有按住。不吞事件。 */
    public boolean release(long nowMs) {
        if (!holding) return false;
        pressSprings.get(holdKey, nowMs).setTarget(0f, nowMs);
        clearHold();
        return true;
    }

    /** 兜底收尾（界面重建、关闭）：直接归零，不播回弹。 */
    public void cancel(long nowMs) {
        if (!holding) return;
        pressSprings.get(holdKey, nowMs).snap(0f);
        clearHold();
    }

    public boolean holding() {
        return holding;
    }

    /**
     * 每帧调用：游标进出正按着的按钮时改弹簧目标、暂停或继续连发计时，并执行本帧该发的连发。
     * 返回本帧实际触发的次数。
     */
    public int pointer(int mx, int my, long nowMs) {
        if (!holding) return 0;
        boolean inside = mx >= holdX && mx <= holdX + holdW && my >= holdY && my <= holdY + holdH;
        if (inside != holdInside) {
            holdInside = inside;
            pressSprings.get(holdKey, nowMs).setTarget(inside ? 1f : 0f, nowMs);
            if (inside) {
                pausedTotalMs += nowMs - pausedAtMs;
            } else {
                pausedAtMs = nowMs;
            }
        }
        if (!inside || repeatAction == null) return 0;
        int due = repeatCurve.firesBy(nowMs - holdStartMs - pausedTotalMs);
        int burst = Math.min(due - repeatFired, MAX_REPEAT_BURST);
        int fired = 0;
        for (int i = 0; i < burst; i++) {
            fired++;
            if (!repeatAction.fire(repeatFired + i + 1)) {
                repeatAction = null;
                repeatCurve = null;
                return fired;
            }
        }
        repeatFired = Math.max(repeatFired, due);
        return fired;
    }

    private void clearHold() {
        holding = false;
        repeatCurve = null;
        repeatAction = null;
        repeatFired = 0;
    }

    // ============ 动作：去抖 / 等待 / 结果 ============

    /** 发起一次动作前调用：还在等回执、或距上次发起不到 {@link #DEBOUNCE_MS} 时返回 false（这一下该吞掉）。 */
    public boolean tryBegin(long actionKey, long nowMs) {
        ActionState st = actions.get(actionKey);
        if (st != null) {
            advance(st, nowMs);
            if (st.phase == Phase.PENDING) return false;
            if (nowMs - st.lastBeginMs < DEBOUNCE_MS) return false;
        } else {
            st = new ActionState();
            actions.put(actionKey, st);
        }
        st.lastBeginMs = nowMs;
        return true;
    }

    /** 已发包，等 {@code requestId} 的回执。 */
    public void pending(long actionKey, int requestId, long nowMs) {
        pending(actionKey, requestId, nowMs, PENDING_TIMEOUT_MS);
    }

    /** 山海：AE 多目標計算可獨立等待較久，交易仍使用原本的五秒逾時。 */
    public void pending(long actionKey, int requestId, long nowMs, long timeoutMs) {
        ActionState st = actions.computeIfAbsent(actionKey, k -> new ActionState());
        if (st.phase == Phase.PENDING) pendingIds.remove(st.requestId);
        st.phase = Phase.PENDING;
        st.requestId = requestId;
        st.pendingAtMs = nowMs;
        st.pendingTimeoutMs = Math.max(1L, timeoutMs);
        pendingIds.put(requestId, actionKey);
    }

    /** 回执到了：对得上还在等的请求就开始闪色并返回 true；未知、已超时的 id 返回 false。 */
    public boolean resolve(int requestId, Tone tone, long nowMs) {
        Long key = pendingIds.get(requestId);
        if (key == null) return false;
        ActionState st = actions.get(key);
        if (st != null) advance(st, nowMs);
        if (st == null || st.phase != Phase.PENDING || st.requestId != requestId) {
            pendingIds.remove(requestId);
            return false;
        }
        pendingIds.remove(requestId);
        startFlash(st, tone, nowMs);
        return true;
    }

    /** 不经回执直接闪（本地预检就失败的情况）。正在等的回执作废。 */
    public void flash(long actionKey, Tone tone, long nowMs) {
        ActionState st = actions.computeIfAbsent(actionKey, k -> new ActionState());
        if (st.phase == Phase.PENDING) pendingIds.remove(st.requestId);
        startFlash(st, tone, nowMs);
    }

    /** 小幅横抖一下、不压下：忙碌中再点、数量到边界、按钮不可用。 */
    public void nudge(long pressKey, long nowMs) {
        nudgeAtMs.put(pressKey, nowMs);
    }

    public Phase phase(long actionKey, long nowMs) {
        ActionState st = actions.get(actionKey);
        if (st == null) return Phase.IDLE;
        advance(st, nowMs);
        return st.phase;
    }

    /** 正在闪的颜色；没在闪返回 NEUTRAL。 */
    public Tone flashTone(long actionKey, long nowMs) {
        ActionState st = actions.get(actionKey);
        if (st == null) return Tone.NEUTRAL;
        advance(st, nowMs);
        return st.phase == Phase.FLASH ? st.tone : Tone.NEUTRAL;
    }

    /** 每帧调用：结算超时与闪色到期，清掉久未使用的弹簧与状态。 */
    public void tick(long nowMs) {
        pressSprings.sweep(nowMs);
        Iterator<ActionState> it = actions.values().iterator();
        while (it.hasNext()) {
            ActionState st = it.next();
            advance(st, nowMs);
            if (st.phase == Phase.IDLE
                    && nowMs - st.lastBeginMs > ACTION_FORGET_MS
                    && nowMs - st.flashAtMs > ACTION_FORGET_MS) {
                it.remove();
            }
        }
        nudgeAtMs.values().removeIf(at -> nowMs - at >= NUDGE_MS);
    }

    private void advance(ActionState st, long nowMs) {
        if (st.phase == Phase.PENDING && nowMs - st.pendingAtMs >= st.pendingTimeoutMs) {
            pendingIds.remove(st.requestId);
            startFlash(st, Tone.NEUTRAL, st.pendingAtMs + st.pendingTimeoutMs);
        }
        if (st.phase == Phase.FLASH && nowMs - st.flashAtMs >= FLASH_MS) {
            st.phase = Phase.IDLE;
        }
    }

    private static void startFlash(ActionState st, Tone tone, long atMs) {
        st.phase = Phase.FLASH;
        st.tone = tone;
        st.flashAtMs = atMs;
    }

    // ============ 画法 ============

    /**
     * 本帧画法。{@code pressKey} 管压下与轻抖，{@code actionKey} 管等待与闪色（普通按钮两者传同一个）。
     * {@code reduceMotion} 时不缩放、不抖、流光不走，颜色闪保留。
     */
    public Visual visual(long pressKey, long actionKey, boolean enabled, long nowMs, boolean reduceMotion) {
        float press = enabled ? pressSprings.valueOr(pressKey, 0f, nowMs) : 0f;
        float shake = 0f;
        Long nudgeAt = nudgeAtMs.get(pressKey);
        if (nudgeAt != null) shake += shakeOffset(nowMs - nudgeAt, NUDGE_PX, NUDGE_MS);
        Tone tone = Tone.NEUTRAL;
        float mix = 0f;
        boolean pending = false;
        float pendingPhase = 0f;
        ActionState st = actions.get(actionKey);
        if (st != null) {
            advance(st, nowMs);
            if (st.phase == Phase.FLASH) {
                long elapsed = nowMs - st.flashAtMs;
                tone = st.tone;
                // 超时收尾只淡淡灰一下，不当成失败
                mix = flashEnvelope(elapsed) * (tone == Tone.NEUTRAL ? 0.6f : 1f);
                if (tone == Tone.FAILED) shake += shakeOffset(elapsed, SHAKE_PX, SHAKE_MS);
            } else if (st.phase == Phase.PENDING) {
                pending = true;
                pendingPhase = reduceMotion ? 0f
                        : (float) ((nowMs - st.pendingAtMs) % PENDING_PERIOD_MS) / PENDING_PERIOD_MS;
            }
        }
        if (reduceMotion) return new Visual(press, 1f, 0f, tone, mix, pending, pendingPhase);
        return new Visual(press, pressScale(press), shake, tone, mix, pending, pendingPhase);
    }

    /** 压下缩放：1 → 0.95；回弹的负值让按钮略微胀出（最多 1.03）。 */
    public static float pressScale(float p) {
        float c = p < -0.6f ? -0.6f : (p > 1.2f ? 1.2f : p);
        return 1f - 0.05f * c;
    }

    /** 衰减正弦横抖：起点、终点都是 0，振幅不超过 {@code amplitude}。 */
    public static float shakeOffset(long elapsedMs, float amplitude, long durationMs) {
        if (elapsedMs <= 0L || elapsedMs >= durationMs) return 0f;
        double t = elapsedMs / 1000.0;
        return (float) (amplitude * Math.exp(-elapsedMs / 110.0) * Math.sin(2.0 * Math.PI * 22.0 * t));
    }

    /** 闪色包络：60ms 升到 1，保持到 260ms，到 {@link #FLASH_MS} 二次淡出为 0。 */
    public static float flashEnvelope(long elapsedMs) {
        if (elapsedMs <= 0L || elapsedMs >= FLASH_MS) return 0f;
        if (elapsedMs < FLASH_RISE_MS) return elapsedMs / (float) FLASH_RISE_MS;
        if (elapsedMs <= FLASH_HOLD_END_MS) return 1f;
        float u = (elapsedMs - FLASH_HOLD_END_MS) / (float) (FLASH_MS - FLASH_HOLD_END_MS);
        return (1f - u) * (1f - u);
    }

    /** 与商店界面的 GREEN / GOLD / RED 同值；超时用中性灰。 */
    public static int toneColor(Tone tone) {
        return switch (tone) {
            case SUCCESS -> 0xFF55FF55;
            case PARTIAL -> 0xFFFFAA00;
            case FAILED -> 0xFFFF5555;
            case NEUTRAL -> 0xFF8A8A8A;
        };
    }

    /** 按闪色把边框/文字色往结果色推。 */
    public static int tint(int baseArgb, Visual v) {
        if (v.flashMix() <= 0f) return baseArgb;
        return GuiMotion.lerpColor(baseArgb, toneColor(v.tone()), v.flashMix());
    }

    /** 按下时底色压暗，最多 35%。 */
    public static int shade(int baseArgb, Visual v) {
        float k = 0.35f * GuiMotion.clamp01(v.press());
        if (k <= 0f) return baseArgb;
        return GuiMotion.lerpColor(baseArgb, SHADE_COLOR, k);
    }
}
