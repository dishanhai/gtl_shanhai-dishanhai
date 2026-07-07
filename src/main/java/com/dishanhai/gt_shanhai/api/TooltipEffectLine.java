package com.dishanhai.gt_shanhai.api;

/** 单行揭示配置 */
public class TooltipEffectLine {
    public final String text;
    public final String revealStyle;
    public final String wobbleStyle;

    public TooltipEffectLine(String text, String revealStyle, String wobbleStyle) {
        this.text = text;
        this.revealStyle = revealStyle;
        this.wobbleStyle = wobbleStyle;
    }

    public TooltipEffectLine(String text) {
        this(text, "body_silver", "ultimate");
    }
}
