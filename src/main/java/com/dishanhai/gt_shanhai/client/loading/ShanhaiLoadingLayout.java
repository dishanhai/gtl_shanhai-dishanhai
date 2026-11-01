package com.dishanhai.gt_shanhai.client.loading;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** 山海：窄視窗仍保留完整字串，過長內容逐行換行。 */
public final class ShanhaiLoadingLayout {
    private ShanhaiLoadingLayout() {}

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
