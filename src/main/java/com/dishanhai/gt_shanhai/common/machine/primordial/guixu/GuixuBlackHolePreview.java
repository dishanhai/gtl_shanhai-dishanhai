package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuContainmentField.Status;

/** Render-only timeline for the Kerr and Penrose effects. */
public final class GuixuBlackHolePreview {
    private static final int INNER_RECOVERY_TICK = 120;
    private static final int OUTER_INJECTION_TICK = 40;
    private static final int OUTER_RECOVERY_TICK = 160;
    private static final int DECLINE_TICK = 230;
    private static final int UNSTABLE_TICK = 330;
    private static final int COLLAPSE_TICK = 390;
    public static final int DURATION_TICKS = 440;

    private GuixuBlackHolePreview() {}

    public record Frame(int spin, Status status, float stability, float innerOrbit, float outerOrbit,
                        long innerRecovery, long outerRecovery, long collapse) {}

    public static Frame sample(long start, double tick) {
        double age = tick - start;
        if (start < 0 || age < 0 || age >= DURATION_TICKS) return null;
        Status status;
        float stability;
        if (age >= COLLAPSE_TICK) {
            status = Status.CLOSED;
            stability = 0;
        } else if (age >= UNSTABLE_TICK) {
            status = Status.UNSTABLE;
            stability = (float) (UNSTABLE_TICK - age);
        } else if (age >= DECLINE_TICK) {
            status = Status.CONTAINED;
            stability = 100 * (1 - unit(age, DECLINE_TICK, UNSTABLE_TICK));
        } else {
            status = age >= OUTER_RECOVERY_TICK ? Status.HYPERSTABLE : Status.CONTAINED;
            stability = 100;
        }

        int initialSpin = GuixuKerrOrbit.INITIAL_SPIN;
        int spin = age >= COLLAPSE_TICK ? initialSpin : Math.round(initialSpin
                + 76 * unit(age, 0, INNER_RECOVERY_TICK)
                - 22 * unit(age, INNER_RECOVERY_TICK, OUTER_RECOVERY_TICK));
        return new Frame(spin, status, stability, orbit(age, 0, INNER_RECOVERY_TICK),
                orbit(age, OUTER_INJECTION_TICK, OUTER_RECOVERY_TICK),
                start + INNER_RECOVERY_TICK, start + OUTER_RECOVERY_TICK, start + COLLAPSE_TICK);
    }

    private static float orbit(double tick, int start, int end) {
        return tick < start || tick >= end ? 0 : 1 + GuixuKerrOrbit.STEPS * unit(tick, start, end);
    }

    private static float unit(double tick, int start, int end) {
        return (float) Math.clamp((tick - start) / (end - start), 0, 1);
    }
}
