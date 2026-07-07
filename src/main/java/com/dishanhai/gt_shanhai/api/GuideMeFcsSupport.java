package com.dishanhai.gt_shanhai.api;

public final class GuideMeFcsSupport {

    private GuideMeFcsSupport() {}

    public static LayoutTextResult prepareLayoutText(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return new LayoutTextResult(false, rawText);
        }

        TextFormatParser.ParseResult parsed = TextFormatParser.parseFormatting(rawText);
        if (parsed.ampIdx < 0 || rawText.equals(parsed.cleanText)) {
            return new LayoutTextResult(false, rawText);
        }

        TextFormatParser.rememberFcsMapping(parsed.cleanText, rawText);
        return new LayoutTextResult(true, parsed.cleanText);
    }

    public record LayoutTextResult(boolean handled, String layoutText) {}
}
