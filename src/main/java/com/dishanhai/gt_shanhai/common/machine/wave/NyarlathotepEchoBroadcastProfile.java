package com.dishanhai.gt_shanhai.common.machine.wave;

/** 山海：回響透鏡的純參數；計算有效數量，不修改或截斷實際物品堆疊。 */
public final class NyarlathotepEchoBroadcastProfile {
    public record Profile(int effectiveLenses, int radius, int outputMultiplier, int powerLevel, double selfFactor) {}

    private NyarlathotepEchoBroadcastProfile() {}

    public static Profile calculate(int storedLenses, boolean guixuOnline, int tier) {
        int lenses = Math.max(0, Math.min(guixuOnline ? 64 : 16, storedLenses));
        int safeTier = Math.max(1, Math.min(16, tier));
        int ordinaryRadius = Math.min(320, 16 + safeTier * 16 + Math.min(16, lenses) * 10);
        int extra = Math.max(0, lenses - 16);
        int radius = ordinaryRadius + (1200 - ordinaryRadius) * extra / 48;
        int output = lenses == 0 ? 2 : 3 + extra / 24;
        int power = Math.min(100, safeTier * 10 + 10);
        return new Profile(lenses, radius, output, power, 1.0 + extra / 12.0);
    }
}
