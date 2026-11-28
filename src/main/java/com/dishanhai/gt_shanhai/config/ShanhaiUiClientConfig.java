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
