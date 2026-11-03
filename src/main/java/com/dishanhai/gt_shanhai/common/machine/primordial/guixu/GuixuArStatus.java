package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai

/** 山海：AR 專用不可變快照；版本化 long[] 沿用現有同步型別，缺失資料不解釋為待機。 */
public record GuixuArStatus(int operation, int activeModules, boolean enabled, int lockSeconds,
                            int containment, float stability, long spacetimePerSecond, long costModifier,
                            int catalysis, int engineLink, int echoLink, int localModules,
                            int remoteModules, int echoModules) {
    public static final int VERSION = 1;
    public static final int WIRE_LENGTH = 15;
    public static final GuixuArStatus UNKNOWN = new GuixuArStatus(-1, -1, false, -1,
            -2, Float.NaN, -1, -1, -1, -1, -1, -1, -1, -1);

    public boolean known() { return operation >= 0; }

    public static GuixuArStatus capture(int operation, int active, boolean enabled, int lock,
                                        GuixuContainmentField field, int engine, int echo,
                                        int local, int remote, int echoes) {
        return new GuixuArStatus(operation, active, enabled, lock,
                field == null ? -1 : field.status().ordinal(),
                field == null ? Float.NaN : field.stability(),
                field == null ? 0 : field.requiredSpacetime(),
                field == null ? 1 : field.costModifier(),
                field == null ? 0 : field.catalysisCounter(), engine, echo, local, remote, echoes);
    }

    public long[] toWire() {
        if (!known()) return new long[0];
        return new long[]{VERSION, operation, activeModules, enabled ? 1 : 0, lockSeconds,
                containment, Float.floatToIntBits(stability), spacetimePerSecond, costModifier,
                catalysis, engineLink, echoLink, localModules, remoteModules, echoModules};
    }

    public static GuixuArStatus fromWire(long[] wire) {
        if (wire == null || wire.length != WIRE_LENGTH || wire[0] != VERSION
                || wire[1] < 0 || wire[1] >= GuixuOperationState.values().length
                || wire[2] < 0 || wire[2] > Integer.MAX_VALUE || wire[3] < 0 || wire[3] > 1
                || wire[4] < 0 || wire[4] > Integer.MAX_VALUE || wire[5] < -1 || wire[5] > 3
                || wire[7] < 0 || wire[8] < 1 || wire[9] < 0
                || wire[9] >= GuixuContainmentField.CATALYSIS_PER_DOUBLING
                || wire[10] < 0 || wire[10] > 2 || wire[11] < 0 || wire[11] > 2
                || wire[12] < 0 || wire[12] > 24 || wire[13] < 0 || wire[13] > 16
                || wire[14] < 0 || wire[14] > 1) return UNKNOWN;
        float stability = Float.intBitsToFloat((int) wire[6]);
        if (wire[5] >= 0 && (!Float.isFinite(stability)
                || stability < GuixuContainmentField.COLLAPSE_STABILITY
                || stability > GuixuContainmentField.MAX_STABILITY)) return UNKNOWN;
        return new GuixuArStatus((int) wire[1], (int) wire[2], wire[3] == 1, (int) wire[4],
                (int) wire[5], stability, wire[7], wire[8], (int) wire[9], (int) wire[10],
                (int) wire[11], (int) wire[12], (int) wire[13], (int) wire[14]);
    }

    public GuixuContainmentBonus bonus() {
        if (lockSeconds > 0) return GuixuContainmentBonus.LOCKED;
        return containment < 0 ? GuixuContainmentBonus.NONE
                : GuixuContainmentBonus.of(GuixuContainmentField.Status.values()[containment], stability);
    }
}
