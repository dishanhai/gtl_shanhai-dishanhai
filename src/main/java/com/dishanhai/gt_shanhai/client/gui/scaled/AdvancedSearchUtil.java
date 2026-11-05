package com.dishanhai.gt_shanhai.client.gui.scaled;

import java.util.List;
import java.util.Locale;

/**
 * 高级子串搜索（移植自 kinetictweaks {@code AdvancedSearchUtil}）。
 * 支持中文/英文/数字/ID 的多词子串匹配（空格分词，全部命中才算匹配）。纯 Java，无依赖。
 */
public class AdvancedSearchUtil {

    public static boolean match(String text, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        if (text == null || text.isBlank()) {
            return false;
        }
        return prepareText(text).matches(prepareQuery(query));
    }

    /** 山海：目录更新时预处理文本，键入时只预处理一次查询，保留拼音桥接语义。 */
    public record PreparedText(String text) {
        public boolean matches(PreparedQuery query) {
            for (String token : query.tokens()) {
                if (!text.contains(token) && !PinyinSearchBridge.contains(text, token)) return false;
            }
            return true;
        }
    }

    public record PreparedQuery(String text, List<String> tokens) {}

    public static PreparedText prepareText(String text) {
        return new PreparedText(normalize(text == null ? "" : text));
    }

    public static PreparedQuery prepareQuery(String query) {
        String clean = normalize(query == null ? "" : query);
        return new PreparedQuery(clean, clean.isEmpty() ? List.of() : List.of(clean.split("\\s+")));
    }

    private static String normalize(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(lower.length());
        boolean lastSpace = true;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isSearchChar(c)) {
                builder.append(c);
                lastSpace = false;
            } else if (!lastSpace) {
                builder.append(' ');
                lastSpace = true;
            }
        }
        return builder.toString().trim();
    }

    private static boolean isSearchChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ':' || c == '@' || c == '#' || isChinese(c);
    }

    private static boolean isChinese(char c) {
        return c >= 19968 && c <= 40959;
    }
}
