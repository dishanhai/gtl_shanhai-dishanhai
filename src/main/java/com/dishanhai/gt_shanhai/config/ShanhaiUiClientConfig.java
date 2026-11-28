package com.dishanhai.gt_shanhai.config;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterBootTimeline;
import net.minecraftforge.common.ForgeConfigSpec;

/** 山海：本機 UI 外觀偏好。 */
public final class ShanhaiUiClientConfig {
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue CLEAR_GLASS, STELLAR_GLASS;
    private static final ForgeConfigSpec.EnumValue<StarLifterMotion> STAR_LIFTER_MOTION;
    private static final ForgeConfigSpec.EnumValue<StarLifterEntrance> STAR_LIFTER_ENTRANCE;
    private static final ForgeConfigSpec.EnumValue<StarLifterLight> STAR_LIFTER_LIGHT;
    private static final ForgeConfigSpec.BooleanValue STAR_LIFTER_INVENTORY;

    public enum StarLifterMotion {
        FULL, STANDARD, MINIMAL, REDUCED;

        public static StarLifterMotion current() { return STAR_LIFTER_MOTION.get(); }

        public void cycle() {
            STAR_LIFTER_MOTION.set(values()[(ordinal() + 1) % values().length]);
            SPEC.save();
        }
    }

    public enum StarLifterEntrance {
        FIRST(StarLifterBootTimeline.DURATION), FULL(StarLifterBootTimeline.DURATION), QUICK(320), MINIMAL(145), OFF(0);

        public final int durationMs;

        StarLifterEntrance(int durationMs) { this.durationMs = durationMs; }

        public static StarLifterEntrance current() { return STAR_LIFTER_ENTRANCE.get(); }

        public void cycle() {
            STAR_LIFTER_ENTRANCE.set(values()[(ordinal() + 1) % values().length]);
            SPEC.save();
        }
    }

    public enum StarLifterLight {
        NORMAL(.55f), STRUCTURE(1.10f), NATURAL(0);

        public final float exposure;

        StarLifterLight(float exposure) { this.exposure = exposure; }

        public static StarLifterLight current() { return STAR_LIFTER_LIGHT.get(); }

        public void cycle() {
            STAR_LIFTER_LIGHT.set(values()[(ordinal() + 1) % values().length]);
            SPEC.save();
        }
    }
    public enum StarLifterVisual {
        PREVIEW, PREVIEW_MOTION, TERMINAL_MOTION, WORLD_STELLAR_EFFECT, WORLD_COLLECTORS, WORLD_DETAIL;

        private ForgeConfigSpec.BooleanValue value;

        public boolean enabled() { return value.get(); }

        public void toggle() {
            value.set(!enabled());
            SPEC.save();
        }
    }

    public enum StellarHudMode {
        ORIGINAL, MINIMAL, STANDARD, ENGINEERING;

        public static StellarHudMode current() { return STELLAR_HUD_MODE.get(); }
    }

    public enum StellarHudMotion {
        ULTRA, HIGH, STANDARD, PERFORMANCE, REDUCED, DISABLED;

        public static StellarHudMotion current() { return STELLAR_HUD_MOTION.get(); }
    }

    public enum StellarHudEffect {
        BRAND, ICONS, SCAN, GLOW, DIGITS;

        private ForgeConfigSpec.BooleanValue value;

        public boolean enabled() { return value.get(); }
    }

    public enum StellarBootEffect {
        ENABLED("enabled"), BRAND("brandIntro"), TEXT("terminalText"), DEVICE("deviceIdentification"),
        SCAN("structureScan"), MORPH("interfaceMorph"), MOTTO("brandMotto");

        private final String key;
        private ForgeConfigSpec.BooleanValue value;

        StellarBootEffect(String key) { this.key = key; }

        public boolean enabled() { return value.get(); }
    }

    private static final ForgeConfigSpec.EnumValue<StellarHudMode> STELLAR_HUD_MODE;
    private static final ForgeConfigSpec.EnumValue<StellarHudMotion> STELLAR_HUD_MOTION;
    private static final ForgeConfigSpec.DoubleValue STELLAR_HUD_BACKGROUND;
    private static final ForgeConfigSpec.BooleanValue STELLAR_HUD_BRAND_DETAILS;
    private static final ForgeConfigSpec.EnumValue<StellarHudMotion> STELLAR_BOOT_QUALITY;
    private static final ForgeConfigSpec.IntValue STELLAR_BOOT_COLD, STELLAR_BOOT_FAST, STELLAR_BOOT_WARM,
            STELLAR_BOOT_CACHE;

    public static StellarHudMotion stellarBootQuality() { return STELLAR_BOOT_QUALITY.get(); }
    public static int stellarBootCold() { return STELLAR_BOOT_COLD.get(); }
    public static int stellarBootFast() { return STELLAR_BOOT_FAST.get(); }
    public static int stellarBootWarm() { return STELLAR_BOOT_WARM.get(); }
    public static int stellarBootCache() { return STELLAR_BOOT_CACHE.get(); }

    public static float stellarHudBackground() { return STELLAR_HUD_BACKGROUND.get().floatValue(); }

    public static boolean stellarHudBrandDetails() { return STELLAR_HUD_BRAND_DETAILS.get(); }

    static {
        var builder = new ForgeConfigSpec.Builder();
        CLEAR_GLASS = builder.define("clearGlass", false);
        STELLAR_GLASS = builder.define("stellarGlass", true);
        builder.push("starLifter");
        STAR_LIFTER_MOTION = builder.defineEnum("motionQuality", StarLifterMotion.STANDARD);
        STAR_LIFTER_ENTRANCE = builder.defineEnum("entrance", StarLifterEntrance.FIRST);
        STAR_LIFTER_LIGHT = builder.defineEnum("observationLight", StarLifterLight.NORMAL);
        STAR_LIFTER_INVENTORY = builder.define("inventoryExpanded", true);
        for (var option : StarLifterVisual.values()) {
            option.value = builder.define(option.name().toLowerCase(java.util.Locale.ROOT), true);
        }
        builder.push("jade");
        STELLAR_HUD_MODE = builder.comment("ORIGINAL restores regular Jade. ENGINEERING keeps the details open.")
                .defineEnum("mode", StellarHudMode.STANDARD);
        STELLAR_HUD_MOTION = builder.defineEnum("motion", StellarHudMotion.STANDARD);
        STELLAR_HUD_BACKGROUND = builder.defineInRange("backgroundOpacity", 0.92, 0.0, 1.0);
        STELLAR_HUD_BRAND_DETAILS = builder.define("brandDetails", true);
        for (var effect : StellarHudEffect.values()) {
            effect.value = builder.define(effect.name().toLowerCase(java.util.Locale.ROOT), true);
        }
        builder.push("boot");
        for (var effect : StellarBootEffect.values()) effect.value = builder.define(effect.key, true);
        STELLAR_BOOT_QUALITY = builder.comment("Maximum boot quality. Jade and UUK accessibility preferences also apply.")
                .defineEnum("quality", StellarHudMotion.ULTRA);
        STELLAR_BOOT_COLD = builder.defineInRange("coldBootMs", 720, 600, 800);
        STELLAR_BOOT_FAST = builder.defineInRange("fastBootMs", 320, 250, 400);
        STELLAR_BOOT_WARM = builder.defineInRange("warmBootMs", 100, 80, 120);
        STELLAR_BOOT_CACHE = builder.defineInRange("cacheMs", 1800, 200, 10000);
        builder.pop();
        builder.pop();
        builder.pop();
        SPEC = builder.build();
    }

    private ShanhaiUiClientConfig() {}

    public static boolean starLifterInventoryExpanded() { return STAR_LIFTER_INVENTORY.get(); }

    public static void setStarLifterInventoryExpanded(boolean expanded) {
        STAR_LIFTER_INVENTORY.set(expanded);
        SPEC.save();
    }

    public static boolean clearGlass() { return CLEAR_GLASS.get(); }
    public static void setClearGlass(boolean clear) { CLEAR_GLASS.set(clear); }

    /** 沿用既有設定鍵：true 為中性煙灰，false 為原深藍透明玻璃。 */
    public static boolean stellarGlass() { return STELLAR_GLASS.get(); }
    public static void setStellarGlass(boolean neutral) {
        STELLAR_GLASS.set(neutral);
        SPEC.save();
    }
}
