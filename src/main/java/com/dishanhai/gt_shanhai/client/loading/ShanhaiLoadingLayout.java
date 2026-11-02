package com.dishanhai.gt_shanhai.client.loading;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** 山海：窄視窗仍保留完整字串，過長內容逐行換行。 */
public final class ShanhaiLoadingLayout {
    private ShanhaiLoadingLayout() {}

    /** 原生 GUI 座標，小視窗改排版而不縮字。 */
    public static GlassPanel glassPanel(int guiWidth, int guiHeight) {
        int margin = guiHeight < 240 ? 10 : 16;
        int width = Math.max(1, Math.min(760, guiWidth - margin * 2));
        int height = Math.max(1, Math.min(440, guiHeight - margin * 2));
        int x = (guiWidth - width) / 2, y = (guiHeight - height) / 2;
        int padding = width >= 560 ? 18 : 12;
        int rail = width >= 560 ? 128 : 0;
        int header = height >= 220 ? 56 : 44;
        int contentX = x + padding + (rail > 0 ? rail + 18 : 0);
        int contentTop = y + header + 12;
        int contentWidth = width - padding * 2 - (rail > 0 ? rail + 18 : 0);
        int contentBottom = y + height - 38;
        return new GlassPanel(x, y, width, height, padding, rail, contentX, contentTop,
                contentWidth, contentBottom, Math.max(1, (contentBottom - contentTop - 20) / 16));
    }

    public record GlassPanel(int x, int y, int width, int height, int padding, int railWidth,
                             int contentX, int contentTop, int contentWidth, int contentBottom, int rowCapacity) {
        public int lineHeight() { return 16; }
        public int footerY() { return y + height - 22; }
    }

    public static List<String> wrap(String text, int maxWidth, ToIntFunction<String> width) {
        var lines = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int codepoint = text.codePointAt(offset);
            String character = new String(Character.toChars(codepoint));
            if (line.length() > 0 && (codepoint == '\n' || width.applyAsInt(line + character) > Math.max(1, maxWidth))) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (codepoint != '\n') line.append(character);
            offset += Character.charCount(codepoint);
        }
        if (line.length() > 0) lines.add(line.toString());
        return List.copyOf(lines);
    }
}
