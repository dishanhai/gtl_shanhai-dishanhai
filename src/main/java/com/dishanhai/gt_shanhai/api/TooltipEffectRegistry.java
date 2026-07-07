package com.dishanhai.gt_shanhai.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 物品 tooltip 特效注册中心 */
public class TooltipEffectRegistry {

    private static final Map<String, TooltipEffectConfig> CONFIGS = new HashMap<>();

    public static void register(String itemId, TooltipEffectConfig config) {
        CONFIGS.put(itemId, config);
    }

    public static TooltipEffectConfig get(String itemId) {
        return CONFIGS.get(itemId);
    }

    public static boolean has(String itemId) {
        return CONFIGS.containsKey(itemId);
    }

    public static void remove(String itemId) {
        CONFIGS.remove(itemId);
    }

    public static int count() {
        return CONFIGS.size();
    }

    /** 配置定义 */
    public static class TooltipEffectConfig {
        public final String itemId;
        public final long revealSpeedMs;
        public final int obfuscateCount;
        public final List<TooltipEffectLine> lines;
        public final String hintText;
        public final boolean obfuscateCompletedLines;
        /** true=需 SHIFT 触发，false=需 ALT 触发（默认） */
        public final boolean shiftOnly;

        public TooltipEffectConfig(String itemId, long revealSpeedMs, int obfuscateCount, List<TooltipEffectLine> lines) {
            this(itemId, revealSpeedMs, obfuscateCount, lines, "§7§o按住 §eALT §7显示终末寄语", false, false);
        }

        public TooltipEffectConfig(String itemId, long revealSpeedMs, int obfuscateCount, List<TooltipEffectLine> lines, String hintText) {
            this(itemId, revealSpeedMs, obfuscateCount, lines, hintText, false, false);
        }

        public TooltipEffectConfig(String itemId, long revealSpeedMs, int obfuscateCount, List<TooltipEffectLine> lines, String hintText, boolean obfuscateCompletedLines) {
            this(itemId, revealSpeedMs, obfuscateCount, lines, hintText, obfuscateCompletedLines, false);
        }

        public TooltipEffectConfig(String itemId, long revealSpeedMs, int obfuscateCount, List<TooltipEffectLine> lines, String hintText, boolean obfuscateCompletedLines, boolean shiftOnly) {
            this.itemId = itemId;
            this.revealSpeedMs = revealSpeedMs > 0 ? revealSpeedMs : 4000;
            this.obfuscateCount = Math.max(0, obfuscateCount);
            this.lines = lines;
            this.hintText = hintText != null && !hintText.isEmpty() ? hintText : "§7§o按住 §eALT §7显示终末寄语";
            this.obfuscateCompletedLines = obfuscateCompletedLines;
            this.shiftOnly = shiftOnly;
        }
    }
}
