package com.dishanhai.gt_shanhai.client.gui.scaled;

/**
 * 可中途改目标的单弹簧。改目标时 value 与 velocity 原样保留，所以被打断的动画从当前状态接着走，不会跳也不会重播。
 *
 * <p>积分与 {@link GuiRenderUtil.FluidSpring} 相同：显式积分，单步超过 50ms 截断，再拆成 4ms 子步。
 * 时间用毫秒，调用方传 {@code System.currentTimeMillis()}（或任何单调毫秒）。</p>
 */
public final class Spring {

    /**
     * @param response       近似到位时间（秒），越小越快
     * @param damping        阻尼比，1.0 为临界阻尼（不回弹），0.92 有极轻微回弹
     * @param settleEps      离目标小于它才可能判定静止（与 value 同单位）
     * @param settleVelocity 速度小于它才可能判定静止
     */
    public record Preset(double response, double damping, float settleEps, float settleVelocity) {}

    /** 卡片、页签、按钮 hover。 */
    public static final Preset HOVER = new Preset(0.10, 1.0, 0.0015f, 0.02f);
    /** 浮层开关、开店、其他界面开场。 */
    public static final Preset PANEL = new Preset(0.20, 0.92, 0.0015f, 0.02f);
    /** 页签高亮块的 x、宽（像素量级）。 */
    public static final Preset INDICATOR = new Preset(0.18, 0.92, 0.05f, 1.0f);
    /** 网格、详情列滚动（像素量级）。 */
    public static final Preset SCROLL = new Preset(0.16, 1.0, 0.05f, 1.0f);
    /** 卡片错峰入场，无回弹，透明度不会超过 1。 */
    public static final Preset STAGGER = new Preset(0.20, 1.0, 0.0015f, 0.02f);

    private static final double MAX_STEP_S = 0.05;
    private static final double SUBSTEP_S = 0.004;

    private final double stiffness;
    private final double drag;
    private final float settleEps;
    private final float settleVelocity;

    private float value;
    private float velocity;
    private float target;
    private long lastMs;
    private boolean settled = true;

    private boolean hasPending;
    private float pendingTarget;
    private long pendingAtMs;

    public Spring(Preset preset) {
        this(preset, 0f);
    }

    public Spring(Preset preset, float initial) {
        double omega = 2.0 * Math.PI / preset.response();
        this.stiffness = omega * omega;
        this.drag = 2.0 * preset.damping() * omega;
        this.settleEps = preset.settleEps();
        this.settleVelocity = preset.settleVelocity();
        this.value = initial;
        this.target = initial;
    }

    /** 开场弹簧：从 0 起弹向 1，起点取当前 {@code System.currentTimeMillis()}。字段初始化里直接用。 */
    public static Spring opening(Preset preset) {
        Spring s = new Spring(preset);
        s.setTarget(1f, System.currentTimeMillis());
        return s;
    }

    /** 立刻改目标，value 与 velocity 保持。非有限值忽略。 */
    public void setTarget(float newTarget, long nowMs) {
        if (!Float.isFinite(newTarget)) return;
        if (this.settled) this.lastMs = nowMs;
        this.hasPending = false;
        this.target = newTarget;
        this.settled = reachedTarget();
        if (this.settled) {
            this.value = this.target;
            this.velocity = 0f;
        }
    }

    /** {@code delayMs} 之后才开始朝新目标走，之前保持当前轨迹（错峰用）。{@code delayMs<=0} 等同 {@link #setTarget}。 */
    public void setTargetAfter(float newTarget, long nowMs, long delayMs) {
        if (delayMs <= 0L) {
            setTarget(newTarget, nowMs);
            return;
        }
        if (!Float.isFinite(newTarget)) return;
        if (this.settled) this.lastMs = nowMs;
        this.hasPending = true;
        this.pendingTarget = newTarget;
        this.pendingAtMs = nowMs + delayMs;
        this.settled = false;
    }

    /** value=目标=给定值，速度清零，立即静止，挂起的延迟目标作废。 */
    public void snap(float v) {
        if (!Float.isFinite(v)) return;
        this.value = v;
        this.target = v;
        this.velocity = 0f;
        this.hasPending = false;
        this.settled = true;
    }

    /** 推进到 {@code nowMs}。静止后重复调用无副作用。 */
    public void update(long nowMs) {
        if (this.settled) return;
        double dt = (nowMs - this.lastMs) / 1000.0;
        this.lastMs = nowMs;
        if (this.hasPending) {
            if (nowMs < this.pendingAtMs) return;
            this.hasPending = false;
            this.target = this.pendingTarget;
        }
        if (dt <= 0.0) return;
        if (dt > MAX_STEP_S) dt = MAX_STEP_S;

        int steps = Math.max(1, (int) Math.ceil(dt / SUBSTEP_S));
        double h = dt / steps;
        for (int i = 0; i < steps; i++) {
            double accel = -this.stiffness * (this.value - this.target) - this.drag * this.velocity;
            this.velocity += (float) (accel * h);
            this.value += (float) (this.velocity * h);
        }

        if (!Float.isFinite(this.value) || !Float.isFinite(this.velocity) || reachedTarget()) {
            this.value = this.target;
            this.velocity = 0f;
            this.settled = true;
        }
    }

    public float value() {
        return this.value;
    }

    public float target() {
        return this.target;
    }

    public boolean settled() {
        return this.settled;
    }

    private boolean reachedTarget() {
        return Math.abs(this.value - this.target) < this.settleEps
                && Math.abs(this.velocity) < this.settleVelocity;
    }
}
