package com.dishanhai.gt_shanhai.common.saveedit;

import java.util.Objects;
import java.util.function.BiFunction;

/** Resolves save editor messages against the active client language. */
public final class ShanhaiSaveEditText {
    private ShanhaiSaveEditText() {}
    private static volatile BiFunction<String, Object[], String> resolver = (key, args) -> key;

    public static void install(BiFunction<String, Object[], String> translator) {
        resolver = Objects.requireNonNull(translator);
    }

    public static String text(String key) {
        return key == null || !key.startsWith("gt_shanhai.saveedit.")
                ? key : resolver.apply(key, new Object[0]);
    }
}
