package com.dishanhai.gt_shanhai.integration.enhancedcore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** 山海：NBT writeUTF 使用 modified UTF-8；顯示摘要與完整搜尋索引分開保存。 */
final class EnhancedCoreCloudLibraryText {
    static final int DISPLAY_BYTES = 24000;
    static final int NBT_BYTES = 60000;

    private EnhancedCoreCloudLibraryText() {}

    static long modifiedUtfBytes(String value) {
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) bytes += charBytes(value.charAt(index));
        return bytes;
    }

    private static int charBytes(char value) { return value >= 1 && value <= 127 ? 1 : value <= 2047 ? 2 : 3; }

    static String cap(String value, int budget) {
        if (budget < 0) throw new IllegalArgumentException();
        return value.substring(0, end(value, 0, budget));
    }

    private static int end(String value, int start, int budget) {
        int index = start, bytes = 0;
        while (index < value.length()) {
            int width = Character.isHighSurrogate(value.charAt(index)) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1)) ? 2 : 1;
            int next = charBytes(value.charAt(index)) + (width == 2 ? charBytes(value.charAt(index + 1)) : 0);
            if (bytes > budget - next) break;
            bytes += next; index += width;
        }
        return index;
    }

    static List<String> chunks(String value, int budget) {
        if (budget < 6) throw new IllegalArgumentException();
        var chunks = new ArrayList<String>(); int start = 0;
        while (start < value.length()) {
            int end = end(value, start, budget);
            chunks.add(value.substring(start, end)); start = end;
        }
        return List.copyOf(chunks);
    }

    /** Cap the component's text, then serialize again; truncating serialized JSON corrupts escaping. */
    static String json(String text, int budget, Function<String, String> serializer) {
        String complete = serializer.apply(text);
        if (modifiedUtfBytes(complete) <= budget) return complete;
        int low = 0, high = text.codePointCount(0, text.length());
        String best = serializer.apply("");
        if (modifiedUtfBytes(best) > budget) throw new IllegalArgumentException();
        while (low <= high) {
            int middle = (low + high) >>> 1;
            String candidate = serializer.apply(text.substring(0, text.offsetByCodePoints(0, middle)));
            if (modifiedUtfBytes(candidate) <= budget) { best = candidate; low = middle + 1; }
            else high = middle - 1;
        }
        return best;
    }

    /** Transient TerminalSide.keys is not serialized; joining preserves cross-chunk substring matches. */
    static String terminalKeys(String registryKeys, boolean mother, String prefix,
                               List<String> inputNames, List<String> outputNames) {
        if (!mother || !(prefix.equals("input") || prefix.equals("output"))) return registryKeys;
        var side = prefix.equals("input") ? inputNames : outputNames;
        return registryKeys + '\n' + String.join("", side).toLowerCase(Locale.ROOT);
    }
}
