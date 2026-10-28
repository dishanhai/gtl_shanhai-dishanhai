package com.dishanhai.gt_shanhai.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** 山海：歸墟動態特效及黑洞的客戶端設定；不依賴來源重寫模組的其他功能。 */
public final class GuixuClientConfig {
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue ENABLED, HIDE_BLOCKS, BH_ENABLED, BH_FALLBACK;
    private static final ForgeConfigSpec.ConfigValue<String> BH_CENTER;
    private static final ForgeConfigSpec.DoubleValue BH_SCALE, BH_STRENGTH;
    static {
        var builder = new ForgeConfigSpec.Builder();
        builder.push("hostfx");
        ENABLED = builder.comment("归墟视界枢核动态特效").define("enabled", true);
        HIDE_BLOCKS = builder.comment("动态特效启用时暂藏客户端静态方块，失效时还原").define("hideRealBlocks", true);
        builder.pop().push("debug");
        BH_ENABLED = builder.define("blackhole_enabled", true);
        BH_CENTER = builder.comment("独立预览黑洞的世界座标 x y z；空字串停用预览").define("blackhole_center", "");
        BH_SCALE = builder.defineInRange("blackhole_scale", 1.0D, 0.05D, 8.0D);
        BH_STRENGTH = builder.defineInRange("blackhole_strength", 1.0D, 0.0D, 2.0D);
        BH_FALLBACK = builder.define("blackhole_fallback_texture", true);
        builder.pop();
        SPEC = builder.build();
    }
    private GuixuClientConfig() {}
    public static boolean hostFxEnabled() { return ENABLED.get(); }
    public static boolean hostFxHideRealBlocks() { return HIDE_BLOCKS.get(); }
    public static boolean blackholeEnabled() { return BH_ENABLED.get(); }
    public static boolean blackholeFallbackTexture() { return BH_FALLBACK.get(); }
    public static String blackholeCenter() { return BH_CENTER.get(); }
    public static double blackholeScale() { return BH_SCALE.get(); }
    public static double blackholeStrength() { return BH_STRENGTH.get(); }
}
