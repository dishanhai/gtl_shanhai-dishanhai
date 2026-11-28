package com.dishanhai.gt_shanhai.client.gui.starlifter;

import net.minecraft.network.chat.Component;

/** Localized manufacturer identity shared by the engineering surfaces. */
public final class CuhiIdentity {
    private static final String KEY = "gt_shanhai.cuhi.";

    public enum Scale { FULL, STANDARD, COMPACT, MICRO }

    private CuhiIdentity() {}

    public static Component name() { return text("name"); }
    public static Component division() { return text("division"); }
    public static Component signature() { return text("signature"); }
    public static Component motto() { return text("motto"); }

    public static Component text(String key) {
        return Component.translatable(KEY + key);
    }

    public static Component wordmark(Scale scale) {
        return switch (scale) {
            case FULL -> name();
            case STANDARD -> division();
            case COMPACT, MICRO -> text("short");
        };
    }
}
