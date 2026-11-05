package com.dishanhai.gt_shanhai.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** 山海：本機 UI 外觀偏好。 */
public final class ShanhaiUiClientConfig {
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue CLEAR_GLASS, STELLAR_GLASS;

    static {
        var builder = new ForgeConfigSpec.Builder();
        CLEAR_GLASS = builder.define("clearGlass", false);
        STELLAR_GLASS = builder.define("stellarGlass", true);
        SPEC = builder.build();
    }

    private ShanhaiUiClientConfig() {}

    public static boolean clearGlass() { return CLEAR_GLASS.get(); }
    public static void setClearGlass(boolean clear) { CLEAR_GLASS.set(clear); }

    /** 沿用既有設定鍵：true 為中性煙灰，false 為原深藍透明玻璃。 */
    public static boolean stellarGlass() { return STELLAR_GLASS.get(); }
    public static void setStellarGlass(boolean neutral) {
        STELLAR_GLASS.set(neutral);
        SPEC.save();
    }
}
