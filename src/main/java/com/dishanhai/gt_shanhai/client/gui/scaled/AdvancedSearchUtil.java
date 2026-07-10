package com.dishanhai.gt_shanhai.client.gui.scaled;

import java.util.ArrayList;
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
        String cleanText = normalize(text);
        String cleanQuery = normalize(query);
        if (cleanQuery.isBlank()) {
            return true;
        }
        String[] queryTokens = cleanQuery.split("\\s+");
        List<String> textTokens = splitTokens(cleanText);
        for (String queryToken : queryTokens) {
            if (!queryToken.isBlank() && !matchToken(cleanText, textTokens, queryToken)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchToken(String cleanText, List<String> textTokens, String queryToken) {
        if (cleanText.contains(queryToken)) {
            return true;
        }
        for (String textToken : textTokens) {
            if (textToken.contains(queryToken)) {
                return true;
            }
        }
        // 纯子串未命中：用 JECharacters 拼音匹配兜底（全拼/首字母/汉字），
        // 未装 JECharacters 时 contains 恒 false，等价于退回纯子串搜索。
        return PinyinSearchBridge.contains(cleanText, queryToken);
    }

    private static List<String> splitTokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (String part : text.split("\\s+")) {
            if (!part.isBlank()) {
                tokens.add(part);
            }
        }
        return tokens;
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
