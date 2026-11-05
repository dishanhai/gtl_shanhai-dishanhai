package com.dishanhai.gt_shanhai.common.machine.part.ui;

/** 山海：將淺色格式碼換成可讀深色，保留選取狀態、警告語義及粗體等格式。 */
public final class StellarGlassText {
    private StellarGlassText() {}

    /** 煙灰底改用淺色語義字，保留選取、告警與粗體格式。 */
    public static String forSmokeGlass(String text) {
        StringBuilder result = null;
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) != '§') continue;
            char code = Character.toLowerCase(text.charAt(++i));
            char replacement = switch (code) {
                case '0', '8' -> '7';
                case '1' -> '9';
                case '2' -> 'a';
                case '3' -> 'b';
                case '4' -> 'c';
                case '5' -> 'd';
                case '6' -> 'e';
                case 'f' -> 'r';
                default -> code;
            };
            if (replacement != code) {
                if (result == null) result = new StringBuilder(text);
                result.setCharAt(i, replacement);
            }
        }
        return result == null ? text : result.toString();
    }

    public static String forClearGlass(String text) {
        StringBuilder result = null;
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) != '§') continue;
            char code = Character.toLowerCase(text.charAt(++i));
            char replacement = switch (code) {
                case '7' -> '8';
                case '9' -> '1';
                case 'a' -> '2';
                case 'b' -> '3';
                case 'c' -> '4';
                case 'd' -> '5';
                case 'e' -> '6';
                case 'f' -> 'r';
                default -> code;
            };
            if (replacement != code) {
                if (result == null) result = new StringBuilder(text);
                result.setCharAt(i, replacement);
            }
        }
        return result == null ? text : result.toString();
    }
}
