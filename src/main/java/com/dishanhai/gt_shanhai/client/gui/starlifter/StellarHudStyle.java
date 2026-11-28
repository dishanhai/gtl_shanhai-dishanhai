package com.dishanhai.gt_shanhai.client.gui.starlifter;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTelemetry.State;
import com.dishanhai.gt_shanhai.config.ShanhaiUiClientConfig.StellarHudMotion;
import com.dishanhai.sh_better_ui.client.api.ShBetterUiApi;
import com.dishanhai.sh_better_ui.client.gui.scaled.UiPreferences;
import com.dishanhai.sh_better_ui.theme.token.ThemeTokens;
import net.minecraft.util.Mth;

final class StellarHudStyle {
    static final int BACKGROUND = 0x080f19, SURFACE = 0x101f30, EDGE = 0x29465b,
            TEXT = 0xe3f5ff, SECONDARY = 0x8ba3b8, SIGNATURE = 0x7195ad, DATA = 0xc6edff, BLUE = 0x69cfff;

    static final long COLLAPSE = 175, VALUE = 150;
    static final int LINE = 12;

    record Palette(int edge, int accent, int muted, StellarHudMotion motion) {
        boolean animated() { return motion != StellarHudMotion.DISABLED; }
        boolean spatial() { return motion.ordinal() < StellarHudMotion.REDUCED.ordinal(); }
        boolean liveIcons() { return motion.ordinal() <= StellarHudMotion.STANDARD.ordinal(); }
        boolean rich() { return motion.ordinal() <= StellarHudMotion.HIGH.ordinal(); }
        int orbitSegments() {
            return switch (motion) {
                case ULTRA -> 48;
                case HIGH -> 32;
                default -> 16;
            };
        }
    }

    private StellarHudStyle() {}

    static Palette resolve(StellarHudMotion requested, boolean jadeMotion) {
        var scope = ShBetterUiApi.themes().global();
        var preferences = UiPreferences.configured().withTheme(scope);
        var quality = requested;
        if (!jadeMotion) quality = StellarHudMotion.DISABLED;
        else if (preferences.reducedMotion() && quality.ordinal() < StellarHudMotion.REDUCED.ordinal()) {
            quality = StellarHudMotion.REDUCED;
        } else if (scope.preferences().lowPerformance() && quality.ordinal() < StellarHudMotion.PERFORMANCE.ordinal()) {
            quality = StellarHudMotion.PERFORMANCE;
        }
        var motion = ShBetterUiApi.motion();
        int accent = motion.lerpColor(0xff000000 | BLUE, scope.resolve(ThemeTokens.ACCENT).argb(), .24f);
        int edge = motion.lerpColor(0xff000000 | EDGE, accent, preferences.highContrast() ? .65f : .18f);
        return new Palette(edge, accent, preferences.highContrast() ? DATA : SECONDARY, quality);
    }

    static int stateColor(State state) {
        return switch (state) {
            case PROCESSING -> 0x77efbc;
            case HIGH_LOAD -> 0x9ef6e4;
            case INITIALIZING -> BLUE;
            case FAULT -> 0xff626e;
            case UNFORMED, DATA_UNAVAILABLE, EXPIRED -> 0xffb65c;
            case STANDBY -> 0x829eb3;
            case OFFLINE, DISCONNECTED, UNKNOWN, WAITING_FOR_DATA -> SECONDARY;
        };
    }

    static int color(int rgb, float alpha) {
        return Mth.clamp(Math.round(255 * alpha), 0, 255) << 24 | rgb & 0xffffff;
    }

    static float ease(long age, long delay, long duration) {
        float left = 1 - Mth.clamp((age - delay) / (float) duration, 0, 1);
        return 1 - left * left * left;
    }
}
