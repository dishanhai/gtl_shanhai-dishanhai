package com.dishanhai.gt_shanhai.api;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.*;

/**
 * 山海格式化码引擎 — 参考 ArcaneVortex RainbowTextRenderer
 *
 * 支持自动 & 码检测（mixin 从 FCS 文本直接解析）：
 * &@$ultimate-旋转彩虹
 * &@~#$fox-全效狐哩剑
 */
public class TextFormatParser {

    /**
     * FCS 前缀映射表：cleanText → 原始含 FCS 前缀的完整文本。
     * TextComponentParserMixin 剥离 FCS 前缀后存入此表，
     * WobbleFontMixin 渲染时从此表恢复色板信息。
     */
    public static final java.util.Map<String, String> FCS_PREFIX_MAP = new java.util.concurrent.ConcurrentHashMap<>();

    public static class FormatFlags {
        public boolean outline;    // #
        public boolean floatY;    // ~
        public boolean floatX;    // *
        public boolean shake;     // %
        public boolean circle;    // @ (效果前缀)
        public boolean wobbleDynamic; // $@ (色板后的 @ 表示波浪)
        public boolean bounce;    // !
        public boolean scan;      // ^
        public boolean glitch;    // ?
        public boolean breathe;   // +
        public boolean chase;     // >
        public boolean obfuscated; // `
        public String gradientTheme;
        public int[] palette;

        public boolean hasEffect() {
            return outline || floatY || floatX || shake || circle || bounce || wobbleDynamic
                    || scan || glitch || breathe || chase || obfuscated;
        }
    }

    /** 快速检测字符串是否含 & 格式化码 */
    public static boolean containsSpecialFormatting(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("&");
    }

    /**
     * 从可能含 & 码的文本中提取纯文本和效果标记。
     * 格式: &[效果字符][$色板名]-[文本]
     * 例: "&@$ultimate-旋转彩虹" → clean="旋转彩虹" flags={circle, palette=ultimate}
     */
    public static ParseResult parseFormatting(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return new ParseResult("", new FormatFlags(), "", -1);
        }
        int ampIdx = rawText.indexOf('&');
        if (ampIdx < 0) {
            return new ParseResult(rawText, new FormatFlags(), "", -1);
        }

        // 提取 & 前的文本（如 "9.22EX "）
        String prefix = rawText.substring(0, ampIdx);

        // & 前缀: &[效果字符][$色板名]- 中间无空格
        int i = ampIdx + 1;
        int dashIdx = rawText.indexOf('-', i);
        if (dashIdx < i) {
            ParseResult compact = parseCompactFormatting(rawText, ampIdx, prefix);
            return compact != null ? compact : new ParseResult(rawText, new FormatFlags(), prefix, ampIdx);
        }

        String codePart = rawText.substring(i, dashIdx);
        String fcsText = rawText.substring(dashIdx + 1);
        if (fcsText.isEmpty()) fcsText = rawText;
        // cleanText = 前缀 + 解析后文本（用于宽度计算）
        String cleanText = prefix + fcsText;

        FormatFlags flags = parseFlags(codePart);

        rememberFcsMapping(cleanText, rawText);
        return new ParseResult(cleanText, flags, prefix, ampIdx);
    }

    private static ParseResult parseCompactFormatting(String rawText, int ampIdx, String prefix) {
        int i = ampIdx + 1;
        StringBuilder effects = new StringBuilder();
        boolean sawDollar = false;
        while (i < rawText.length()) {
            char c = rawText.charAt(i);
            if (c == '$') {
                sawDollar = true;
                i++;
            } else if (isEffectSymbol(c)) {
                effects.append(c);
                i++;
            } else {
                break;
            }
        }
        if (!sawDollar || i >= rawText.length()) return null;

        String rest = rawText.substring(i);
        String theme = findStylePrefix(rest);
        if (theme == null || theme.isEmpty()) return null;
        String fcsText = rest.substring(theme.length());
        if (fcsText.isEmpty()) return null;

        String codePart = effects + "$" + theme;
        FormatFlags flags = parseFlags(codePart);
        String cleanText = prefix + fcsText;
        rememberFcsMapping(cleanText, rawText);
        return new ParseResult(cleanText, flags, prefix, ampIdx);
    }

    public static void rememberFcsMapping(String cleanText, String rawText) {
        if (cleanText == null || cleanText.isEmpty() || rawText == null || rawText.isEmpty()) return;
        if (!containsSpecialFormatting(rawText) || cleanText.equals(rawText)) return;
        FCS_PREFIX_MAP.put(cleanText, rawText);
    }

    public static String restoreRawFcs(String cleanText) {
        if (cleanText == null || cleanText.isEmpty()) return null;
        return FCS_PREFIX_MAP.get(cleanText);
    }

    private static String findStylePrefix(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String[] names = DShanhaiStyleRegistry.getAllNames();
        String best = null;
        for (String name : names) {
            if (name == null || name.isEmpty()) continue;
            String key = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(key) && (best == null || key.length() > best.length())) {
                best = key;
            }
        }
        return best;
    }

    private static FormatFlags parseFlags(String codePart) {
        FormatFlags flags = new FormatFlags();
        flags.outline = codePart.contains("#");
        flags.floatY = codePart.contains("~");
        flags.floatX = codePart.contains("*");
        flags.shake = codePart.contains("%");
        flags.circle = codePart.contains("@");
        flags.bounce = codePart.contains("!");
        flags.scan = codePart.contains("^");
        flags.glitch = codePart.contains("?");
        flags.breathe = codePart.contains("+");
        flags.chase = codePart.contains(">");
        flags.obfuscated = codePart.contains("`");

        int dollarIdx = codePart.indexOf('$');
        if (dollarIdx >= 0) {
            String theme = codePart.substring(dollarIdx + 1);
            while (!theme.isEmpty() && isEffectSymbol(theme.charAt(0))) {
                if (theme.charAt(0) == '@') flags.wobbleDynamic = true;
                theme = theme.substring(1);
            }
            if (!theme.isEmpty()) {
                if (DShanhaiStyleRegistry.isObfuscatedStyle(theme)) {
                    flags.obfuscated = true;
                }
                flags.gradientTheme = theme;
                flags.palette = DShanhaiStyleRegistry.getRGB(theme.toLowerCase(Locale.ROOT));
            }
        }
        return flags;
    }

    public static String toFcsInsertion(FormatFlags flags) {
        if (flags == null || flags.gradientTheme == null || flags.gradientTheme.isEmpty()) return null;
        StringBuilder info = new StringBuilder("fcs:").append(flags.gradientTheme);
        if (flags.circle) info.append("|@");
        if (flags.floatY) info.append("|~");
        if (flags.floatX) info.append("|*");
        if (flags.shake) info.append("|%");
        if (flags.bounce) info.append("|!");
        if (flags.outline) info.append("|#");
        if (flags.scan) info.append("|^");
        if (flags.glitch) info.append("|?");
        if (flags.breathe) info.append("|+");
        if (flags.chase) info.append("|>");
        if (flags.obfuscated) info.append("|`");
        return info.toString();
    }

    private static boolean isEffectSymbol(char c) {
        switch (c) {
            case '@': case '~': case '#': case '*': case '%': case '!':
            case '^': case '?': case '+': case '>': case '`':
                return true;
            default:
                return false;
        }
    }

    /** 解析结果：纯文本 + 效果标记 */
    public static class ParseResult {
        public final String cleanText;
        public final FormatFlags flags;
        public final String prefix;   // & 前的文本（确保数量等不丢失）
        public final int ampIdx;      // & 的位置，-1 表示无 &

        public ParseResult(String cleanText, FormatFlags flags) {
            this(cleanText, flags, "", -1);
        }

        public ParseResult(String cleanText, FormatFlags flags, String prefix, int ampIdx) {
            this.cleanText = cleanText;
            this.flags = flags;
            this.prefix = prefix;
            this.ampIdx = ampIdx;
        }
    }

    // ===== 效果计算方法 =====

    public static float[] calcCircleOffset(int charIndex, long time) {
        double angle = time * 0.003 + charIndex * 0.5;
        return new float[]{
            (float)(Math.cos(angle) * 3.0),
            (float)(Math.sin(angle) * 3.0)
        };
    }

    public static float calcWaveY(int charIndex, long time) {
        return (float)(Math.sin(charIndex * 0.5 + time * 0.002) * 3.0);
    }

    public static float calcWaveX(int charIndex, long time) {
        return (float)(Math.cos(charIndex * 0.7 + time * 0.0015) * 2.0);
    }

    public static float[] calcShakeOffset(int charIndex, long time) {
        double seed = charIndex * 4729;
        return new float[]{
            (float)(Math.sin(time * 0.02 + seed * 0.001) * (seed % 100) * 0.01),
            (float)(Math.cos(time * 0.02 * 1.7 + seed * 0.002) * (seed % 150) * 0.008)
        };
    }

    public static float calcBounceY(int charIndex, long time) {
        return (float)Math.abs(Math.sin(time * 0.005 + charIndex * 1.2)) * 4.0f;
    }

    public static float calcGlitchX(int charIndex, long time) {
        long phase = (time / 55L) + charIndex * 17L;
        if ((phase % 7L) > 1L) return 0.0f;
        return ((phase % 3L) - 1L) * 1.5f;
    }

    public static float calcGlitchY(int charIndex, long time) {
        long phase = (time / 65L) + charIndex * 23L;
        if ((phase % 9L) > 1L) return 0.0f;
        return ((phase % 3L) - 1L) * 1.0f;
    }

    public static int applyScanColor(int color, int charIndex, int totalLen, long time) {
        if (totalLen <= 0) return color;
        int period = Math.max(totalLen + 8, 12);
        int pos = (int)((time / 45L) % period) - 4;
        int dist = Math.abs(charIndex - pos);
        if (dist > 3) return color;
        float strength = (4.0f - dist) / 4.0f;
        return mixColor(color, 0xFFFFFF, strength * 0.75f);
    }

    public static int applyBreatheColor(int color, int charIndex, long time) {
        double wave = Math.sin(time * 0.003 + charIndex * 0.18);
        float strength = (float)((wave + 1.0D) * 0.22D);
        return mixColor(color, 0xFFFFFF, strength);
    }

    public static int applyChaseColor(int color, int charIndex, int totalLen, long time) {
        if (totalLen <= 0) return color;
        int period = Math.max(totalLen, 1);
        int pos = (int)((time / 70L) % period);
        int dist = Math.abs(charIndex - pos);
        dist = Math.min(dist, period - dist);
        if (dist > 2) return color;
        float strength = (3.0f - dist) / 3.0f;
        return mixColor(color, 0xFFFFFF, strength * 0.9f);
    }

    public static int applyGlitchColor(int color, int charIndex, long time) {
        long phase = (time / 60L) + charIndex * 31L;
        if ((phase % 11L) == 0L) return mixColor(color, 0x55FFFF, 0.65f);
        if ((phase % 13L) == 0L) return mixColor(color, 0xFF5555, 0.55f);
        return color;
    }

    private static int mixColor(int base, int target, float amount) {
        if (amount <= 0.0f) return base;
        if (amount > 1.0f) amount = 1.0f;
        int br = (base >> 16) & 0xFF;
        int bg = (base >> 8) & 0xFF;
        int bb = base & 0xFF;
        int tr = (target >> 16) & 0xFF;
        int tg = (target >> 8) & 0xFF;
        int tb = target & 0xFF;
        int r = (int)(br + (tr - br) * amount);
        int g = (int)(bg + (tg - bg) * amount);
        int b = (int)(bb + (tb - bb) * amount);
        return (r << 16) | (g << 8) | b;
    }

    // ===== Component 构建（备选，不依赖 mixin 时使用） =====

    public static Component createStyled(String text, FormatFlags flags) {
        MutableComponent result = Component.literal("");
        int[] palette = flags != null ? flags.palette : null;
        for (int i = 0; i < text.length(); i++) {
            Style style = Style.EMPTY;
            if (palette != null && palette.length > 0) {
                style = style.withColor(TextColor.fromRgb(palette[i % palette.length]));
            }
            if (flags != null && flags.obfuscated) {
                style = style.withObfuscated(true);
            }
            result.append(Component.literal(String.valueOf(text.charAt(i))).withStyle(style));
        }
        return result;
    }

    /**
     * 解析 & 码并返回 Component。
     * 保留原始 & 码在文本中（FCS mixin 检测用），同时赋予色板颜色（fallback）。
     * Jade 等调用方看到的是带 & 码的原始文本，FCS mixin 自动剥离 + 加效果。
     */
    /**
     * 解析 & 码并返回 Component。
     * 始终保留原始 & 码在文本中——FCS mixin 检测 & 后剥离前缀、应用色板和动效。
     * 无 mixin 的环境显示原始文本（含 & 码），但有颜色 fallback。
     */
    public static Component parse(String input) {
        if (input == null || input.isEmpty()) return Component.literal("");
        ParseResult r = parseFormatting(input);
        return createStyled(input, r.flags);
    }

    /**
     * 解析 & 码并返回仅含纯文本的 Component（无 & 前缀）。
     * 为 mixin 备选路径服务——当 m_272191_ 未被拦截时，m_92893_ 层用此方法
     * 直接替换为干净的 Component，避免显示原始 & 码文本。
     */
    public static Component parseClean(String input) {
        if (input == null || input.isEmpty()) return Component.literal("");
        ParseResult r = parseFormatting(input);
        if (r.cleanText.isEmpty()) return Component.literal("");
        return createStyled(r.cleanText, r.flags);
    }

    public static Component createFcsComponent(String effects, String text, String style) {
        String cleanText = text == null ? "" : text;
        String theme = style == null || style.isEmpty() ? "ultimate" : style;
        String code = effects == null ? "" : effects;
        StringBuilder info = new StringBuilder("fcs:").append(theme);
        appendEffectInfo(info, code, '@');
        appendEffectInfo(info, code, '~');
        appendEffectInfo(info, code, '*');
        appendEffectInfo(info, code, '%');
        appendEffectInfo(info, code, '!');
        appendEffectInfo(info, code, '#');
        appendEffectInfo(info, code, '^');
        appendEffectInfo(info, code, '?');
        appendEffectInfo(info, code, '+');
        appendEffectInfo(info, code, '>');
        appendEffectInfo(info, code, '`');
        return Component.literal(cleanText).withStyle(s -> s.withInsertion(info.toString()));
    }

    private static void appendEffectInfo(StringBuilder info, String code, char symbol) {
        if (code.indexOf(symbol) >= 0) info.append('|').append(symbol);
    }

    /**
     * 对已有的 Component 处理 & 码，返回干净的 xliff 文本 Component。
     * 若无 & 码则返回原 Component。
     */
    public static Component processComponent(Component component) {
        String raw = component.getString();
        if (!containsSpecialFormatting(raw)) return component;
        return parseClean(raw);
    }
}
