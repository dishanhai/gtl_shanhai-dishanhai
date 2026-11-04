package com.dishanhai.gt_shanhai.common.machine.part;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/** 山海：星律告警文案只保留語言鍵。存檔用可翻譯元件 JSON，舊的純文字照字面顯示。 */
public final class StellarPatternWarningText {
    public static final String PREFIX = "gui.gt_shanhai.stellar_stuck.";
    public static final String UNNAMED_CPU = PREFIX + "unnamed_cpu";

    private StellarPatternWarningText() {}

    public static MutableComponent line(String key, Object... args) {
        return Component.translatable(PREFIX + key, args);
    }

    public static MutableComponent join(List<? extends Component> parts) {
        MutableComponent out = Component.empty();
        if (parts == null) return out;
        boolean any = false;
        for (Component part : parts) {
            if (part == null) continue;
            if (any) out.append("\n");
            out.append(part);
            any = true;
        }
        return out;
    }

    public static String store(Component component) {
        if (component == null) return "";
        return Component.Serializer.toJson(component);
    }

    public static Component show(String stored) {
        if (stored == null || stored.isBlank()) return Component.empty();
        if (stored.startsWith(PREFIX)) return Component.translatable(stored);
        if (stored.charAt(0) == '{') {
            try {
                Component parsed = Component.Serializer.fromJson(stored);
                if (parsed != null) return parsed;
            } catch (RuntimeException ignored) {
                // 舊存檔或被截斷的文字仍按字面顯示。
            }
        }
        return Component.literal(stored);
    }
}
