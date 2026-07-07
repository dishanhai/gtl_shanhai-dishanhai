package com.dishanhai.gt_shanhai.api;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 山海动态文字 API 统一入口 —— 对 KubeJS 全面开放。
 * 绑定名: "ShanhaiText"
 */
@SuppressWarnings("unused")
public class ShanhaiTextAPI {

    // =====================
    // 山海自有 API（逐字流动渐变）
    // =====================

    /** 14色全色域逐字流动渐变（排除§0§1） */
    public static Component ultimateRainbow(String text) { return DShanhaiTextUtil.createUltimateRainbow(text); }

    /** 7色彩虹全串同色闪烁 */
    public static Component rainbow(String text) { return DShanhaiTextUtil.createRainbowText(text); }

    /** 7色混淆闪烁彩虹 */
    public static Component obfuscatedRainbow(String text) { return DShanhaiTextUtil.createObfuscatedRainbow(text); }

    /** 金色系逐字渐变 */
    public static Component golden(String text) { return DShanhaiTextUtil.createGoldenText(text); }

    /** 火焰系逐字渐变 */
    public static Component fire(String text) { return DShanhaiTextUtil.createFireText(text); }

    /** 水流系逐字渐变 */
    public static Component water(String text) { return DShanhaiTextUtil.createWaterText(text); }

    /** 魔法系逐字渐变 */
    public static Component magic(String text) { return DShanhaiTextUtil.createMagicText(text); }

    /** 自然系逐字渐变 */
    public static Component nature(String text) { return DShanhaiTextUtil.createNatureText(text); }

    /** 电流系逐字渐变 */
    public static Component electric(String text) { return DShanhaiTextUtil.createElectricText(text); }

    /** 冰霜系逐字渐变 */
    public static Component ice(String text) { return DShanhaiTextUtil.createIceText(text); }

    /** 熔岩系逐字渐变 */
    public static Component lava(String text) { return DShanhaiTextUtil.createLavaText(text); }

    /** 日落系逐字渐变 */
    public static Component sunset(String text) { return DShanhaiTextUtil.createSunsetText(text); }

    /** 极光系逐字渐变 */
    public static Component aurora(String text) { return DShanhaiTextUtil.createAuroraText(text); }

    /** 猩红系逐字渐变 */
    public static Component crimson(String text) { return DShanhaiTextUtil.createCrimsonText(text); }

    /** 霓虹系逐字渐变 */
    public static Component neon(String text) { return DShanhaiTextUtil.createNeonText(text); }

    /** 樱花系逐字渐变 */
    public static Component sakura(String text) { return DShanhaiTextUtil.createSakuraText(text); }

    /** 宇宙星云逐字渐变 */
    public static Component cosmic(String text) { return DShanhaiTextUtil.createCosmicText(text); }

    /** 虚空紫黑逐字渐变 */
    public static Component voidText(String text) { return DShanhaiTextUtil.createVoidText(text); }

    /** 青玉翠光逐字渐变 */
    public static Component jade(String text) { return DShanhaiTextUtil.createJadeText(text); }

    /** 等离子辉光逐字渐变 */
    public static Component plasma(String text) { return DShanhaiTextUtil.createPlasmaText(text); }

    /** 星辉白金逐字渐变 */
    public static Component starlight(String text) { return DShanhaiTextUtil.createStarlightText(text); }

    /** 深渊蓝光逐字渐变 */
    public static Component abyss(String text) { return DShanhaiTextUtil.createAbyssText(text); }

    // =====================
    // 正文柔和渐变（慢速 500ms，去饱和色板，适合大段正文）
    // =====================

    /** 正文金色 */
    public static Component bodyGolden(String text) { return DShanhaiTextUtil.createBodyGolden(text); }
    /** 正文火焰 */
    public static Component bodyFire(String text) { return DShanhaiTextUtil.createBodyFire(text); }
    /** 正文水流 */
    public static Component bodyWater(String text) { return DShanhaiTextUtil.createBodyWater(text); }
    /** 正文魔法 */
    public static Component bodyMagic(String text) { return DShanhaiTextUtil.createBodyMagic(text); }
    /** 正文自然 */
    public static Component bodyNature(String text) { return DShanhaiTextUtil.createBodyNature(text); }
    /** 正文猩红 */
    public static Component bodyCrimson(String text) { return DShanhaiTextUtil.createBodyCrimson(text); }
    /** 正文银色 */
    public static Component bodySilver(String text) { return DShanhaiTextUtil.createBodySilver(text); }
    /** 正文日落 */
    public static Component bodySunset(String text) { return DShanhaiTextUtil.createBodySunset(text); }
    /** 正文极光 */
    public static Component bodyAurora(String text) { return DShanhaiTextUtil.createBodyAurora(text); }
    /** 正文霓虹 */
    public static Component bodyNeon(String text) { return DShanhaiTextUtil.createBodyNeon(text); }
    /** 正文电击 */
    public static Component bodyElectric(String text) { return DShanhaiTextUtil.createBodyElectric(text); }
    /** 正文寒冰 */
    public static Component bodyIce(String text) { return DShanhaiTextUtil.createBodyIce(text); }
    /** 正文熔岩 */
    public static Component bodyLava(String text) { return DShanhaiTextUtil.createBodyLava(text); }
    public static Component bodyCream(String text) { return DShanhaiTextUtil.createBodyCream(text); }
    public static Component bodyAmber(String text) { return DShanhaiTextUtil.createBodyAmber(text); }
    public static Component bodySlate(String text) { return DShanhaiTextUtil.createBodySlate(text); }
    public static Component bodyRose(String text) { return DShanhaiTextUtil.createBodyRose(text); }
    public static Component bodyMoss(String text) { return DShanhaiTextUtil.createBodyMoss(text); }

    /** 自定义色序+间隔的逐字流动渐变 */
    public static Component custom(String text, net.minecraft.ChatFormatting[] colors, int intervalMs, int mask) {
        return DShanhaiTextUtil.createCustomGradient(text, colors, intervalMs, mask);
    }

    /** 自定义 RGB 逐字流动渐变（支持方向控制） */
    public static Component customRGB(String text, int[] rgbColors, int intervalMs, int dir) {
        return DShanhaiTextUtil.createCustomGradientRGB(text, rgbColors, intervalMs, dir);
    }

    /** 设置全局动画速度（毫秒/步，默认 80） */
    public static void setSpeed(long ms) { DShanhaiTextUtil.setSpeed(ms); }

    /** 设置全局流动方向（1 = 右→左，-1 = 左→右） */
    public static void setDirection(int dir) { DShanhaiTextUtil.setDirection(dir); }

    // ========== 多行同步 ==========

    /** 锁定时间戳：同一 tick 内创建的渐变文本共用相位，解决多行不同步 */
    public static void lockTime() { DShanhaiTextUtil.lockTime(); }

    /** 解锁时间戳 */
    public static void unlockTime() { DShanhaiTextUtil.unlockTime(); }

    /**
     * 多行连续渐变：多行文本颜色跨行流动，行尾→行首无缝衔接。
     * @param lines 每行文本
     * @param style 样式名（同 createXxxText，如 "rainbow"、"fire"）
     * @return 每行对应的 Component 数组
     */
    public static Component[] multiLineText(String[] lines, String style) {
        int[] palette = resolvePalette(style);
        if (palette == null) {
            Component[] fallback = new Component[lines.length];
            for (int i = 0; i < lines.length; i++) fallback[i] = Component.literal(lines[i]);
            return fallback;
        }
        return DShanhaiTextUtil.createMultiLineText(lines, palette);
    }

    /**
     * 获取指定样式当前帧的末端颜色（用于填充过渡计算）
     * @param style 样式名
     * @param text 文本内容（用于计算字符偏移）
     */
    public static int getEndColor(String style, String text) {
        int[] palette = resolvePalette(style);
        if (palette == null) return 0xFFFFFF;
        return DShanhaiTextUtil.getPaletteEndColor(palette, text.replace("——", "----").length());
    }

    /**
     * 获取指定样式当前帧的起始颜色
     */
    public static int getStartColor(String style) {
        int[] palette = resolvePalette(style);
        if (palette == null) return 0xFFFFFF;
        return DShanhaiTextUtil.getPaletteStartColor(palette);
    }

    /**
     * 渐变色填充：从 startColor RGB 过渡到 endColor RGB 的文本组件
     * @param text 填充文本（建议 "─"）
     * @param startColor 起始 RGB
     * @param endColor 结束 RGB
     */
    public static Component gradientFiller(String text, int startColor, int endColor) {
        return DShanhaiTextUtil.createGradientFiller(text, startColor, endColor);
    }

    // ========== 色板解析 ==========

    private static int[] resolvePalette(String style) {
        if (style == null) return null;
        // 从 DShanhaiStyleRegistry 统一查表
        return DShanhaiStyleRegistry.getRGB(style);
    }

    // =====================
    // gtlcore TextUtil（全串染色，返回格式化字符串）
    // =====================

    /** gtlcore 彩虹全串染色（返回带§码的字符串） */
    public static String gtlRainbow(String text) { return org.gtlcore.gtlcore.utils.TextUtil.full_color(text); }

    /** gtlcore 金红色系 */
    public static String gtlDarkPurplishRed(String text) { return org.gtlcore.gtlcore.utils.TextUtil.dark_purplish_red(text); }

    /** gtlcore 白蓝色系 */
    public static String gtlWhiteBlue(String text) { return org.gtlcore.gtlcore.utils.TextUtil.white_blue(text); }

    /** gtlcore 紫红色系 */
    public static String gtlPurplishRed(String text) { return org.gtlcore.gtlcore.utils.TextUtil.purplish_red(text); }

    /** gtlcore 金色 */
    public static String gtlGolden(String text) { return org.gtlcore.gtlcore.utils.TextUtil.golden(text); }

    /** gtlcore 深绿色 */
    public static String gtlDarkGreen(String text) { return org.gtlcore.gtlcore.utils.TextUtil.dark_green(text); }

    /** gtlcore 自定义格式化：formatting(text, colors[], intervalMs) */
    public static String gtlFormat(String text, net.minecraft.ChatFormatting[] colors, double intervalMs) {
        return org.gtlcore.gtlcore.utils.TextUtil.formatting(text, colors, intervalMs);
    }

    // =====================
    // gtladditions CommonUtils（返回 Component）
    // =====================

    /** gtladditions 彩虹 Component（200ms 间隔） */
    public static Component addRainbow(String text) {
        return com.gtladd.gtladditions.utils.CommonUtils.createRainbowComponent(text);
    }

    /** gtladditions 混淆彩虹 Component */
    public static Component addObfuscatedRainbow(String text) {
        return com.gtladd.gtladditions.utils.CommonUtils.INSTANCE.createObfuscatedRainbowComponent(text);
    }

    /** gtladditions 混淆删除线 Component */
    public static Component addObfuscatedDelete(String text) {
        return com.gtladd.gtladditions.utils.CommonUtils.INSTANCE.createObfuscatedDeleteComponent(text);
    }

    /** gtladditions 对已有 MutableComponent 套彩虹 */
    public static Component addRainbowOn(MutableComponent component) {
        return com.gtladd.gtladditions.utils.CommonUtils.createLanguageRainbowComponent(component);
    }

    // ========== 工具 ==========

    /** 终极彩虹扭曲动画 */
    public static Component ultimateRainbowDistort(String text) { return DShanhaiTextUtil.createStyled(text, "ultimateRainbow_distort"); }
    /** 正文奶油色扭曲动画 */
    public static Component bodyCreamDistort(String text) { return DShanhaiTextUtil.createStyled(text, "body_cream_distort"); }
    /** 正文银色扭曲动画 */
    public static Component bodySilverDistort(String text) { return DShanhaiTextUtil.createStyled(text, "body_silver_distort"); }

    /** 终极彩虹上下波动文本（KubeJS 入口） */
    public static Component ultimateRainbowWobble(String text) { return DShanhaiTextUtil.createWobbleText(text, "ultimateRainbow"); }
    /** 正文奶油色波动文本 */
    public static Component bodyCreamWobble(String text) { return DShanhaiTextUtil.createWobbleText(text, "body_cream"); }
    /** 通用 wobble 创建（style 为样式名） */
    public static Component wobble(String text, String style) { return DShanhaiTextUtil.createWobbleText(text, style); }

    /** FCS 扫光效果：等价于 &^$style-text */
    public static Component scan(String text, String style) { return fcs("^", text, style); }

    /** FCS 故障效果：等价于 &?$style-text */
    public static Component glitch(String text, String style) { return fcs("?", text, style); }

    /** FCS 呼吸亮度效果：等价于 &+$style-text */
    public static Component breatheFx(String text, String style) { return fcs("+", text, style); }

    /** FCS 追光效果：等价于 &>$style-text */
    public static Component chase(String text, String style) { return fcs(">", text, style); }

    /** FCS 混淆效果：等价于 &`$style-text */
    public static Component obfuscatedFcs(String text, String style) { return fcs("`", text, style); }

    /** 组合 FCS 效果入口，例如 ShanhaiText.fcs("^+`", "文本", "cosmic") */
    public static Component fcs(String effects, String text, String style) {
        return TextFormatParser.createFcsComponent(effects, text, style);
    }


    // ========== 居中逐字揭示 ==========

    /** 居中逐字揭示（默认 body_silver 色板，混淆尾部 3 字符） */
    public static Component reveal(String text, int revealed) {
        return DShanhaiTextUtil.createRevealText(text, revealed, "body_silver", 3);
    }
    /** 居中逐字揭示（指定样式名） */
    public static Component reveal(String text, int revealed, String style) {
        return DShanhaiTextUtil.createRevealText(text, revealed, style, 3);
    }
    /** 居中逐字揭示（完整控制） */
    public static Component reveal(String text, int revealed, String style, int obfuscatedTail) {
        return DShanhaiTextUtil.createRevealText(text, revealed, style, obfuscatedTail);
    }

    /** 将 § 码字符串转为带 Style 的 Component（用于 KubeJS Component.literal 不解析 § 码的问题） */
    public static Component fromLegacy(String legacy) {
        return DShanhaiTextUtil.fromLegacy(legacy);
    }

    // ========== 格式化码解析（参考 ArcaneVortex & 码系统） ==========

    /**
     * 解析含 & 格式化码的文本，返回带效果的 Component。
     * <p>
     * 格式：
     * <pre>
     * &lt;效果字符&gt;$:&lt;色板名&gt;:&lt;文本&gt;
     * </pre>
     * 效果字符：@旋转 ~波浪 *左右 %抖动 #发光 !弹跳
     * <p>
     * 示例：
     * <pre>
     * ShanhaiText.format("&@$:ultimate:旋转彩虹")
     * ShanhaiText.format("&~$:fire:波浪火焰")
     * ShanhaiText.format("&@~#$:fox:全效狐哩剑")
     * </pre>
     */
    public static Component format(String input) {
        return TextFormatParser.parse(input);
    }

    /** 格式化码 + 普通文本混合 */
    public static Component format(String input, String defaultStyle) {
        if (input == null || input.isEmpty()) return Component.literal("");
        if (!input.contains("&") || defaultStyle == null) return DShanhaiTextUtil.createStyled(input, defaultStyle);
        return TextFormatParser.parse(input);
    }

    // ========== 多段拼接（无需 + 拼接字符串） ==========

    /**
     * 按样式名创建文本 Component。addLore 等 JS 辅助函数统一委托到此方法。
     * <p>
     * 用法:
     * <pre>ShanhaiText.styled("彩虹", "rainbow")</pre>
     *
     * @param text  文本内容
     * @param style 样式名，同 {@link DynamicNameRegistry} 支持的样式
     * @return 动态样式的 Component
     */
    public static Component styled(String text, String style) {
        return DShanhaiTextUtil.createStyled(text, style);
    }

    /**
     * 创建正文柔和风格文本（自动添加 body_ 前缀）。
     * <pre>ShanhaiText.body("正文", "silver") → bodySilver 样式</pre>
     */
    public static Component body(String text, String style) {
        return DShanhaiTextUtil.createStyled(text, "body_" + style);
    }

    /**
     * 拼接多段不同样式的文本组件。
     * <p>
     * 用法（KubeJS）:
     * <pre>
     * ShanhaiText.concat(["万态平衡", "大冻结", "创世纪"], ["aurora", "obfuscatedRainbow", "aurora"])
     * </pre>
     *
     * @param texts  各段文本，按显示顺序
     * @param styles 各段样式，与 texts 一一对应（长度不足用 ultimate 补齐）
     * @return 拼接后的 Component
     */
    public static Component concat(String[] texts, String[] styles) {
        MutableComponent result = Component.literal("");
        for (int i = 0; i < texts.length; i++) {
            String style = (i < styles.length) ? styles[i] : "ultimate";
            result.append(DShanhaiTextUtil.createStyled(texts[i], style));
        }
        return result;
    }

    /**
     * 拼接多段同一样式的文本组件（简化版）。
     * <p>
     * 用法:
     * <pre>
     * ShanhaiText.concat(["第一行\\n", "第二行"], "aurora")
     * </pre>
     *
     * @param texts 各段文本
     * @param style 统一样式
     * @return 拼接后的 Component
     */
    public static Component concat(String[] texts, String style) {
        MutableComponent result = Component.literal("");
        String s = style != null ? style : "ultimate";
        for (String t : texts) {
            result.append(DShanhaiTextUtil.createStyled(t, s));
        }
        return result;
    }

    // ========== 行内标记解析 ==========

    /** 开标记字符 → 闭标记字符 */
    private static final char[][] MARKER_PAIRS = {{'{','}'},{'{','}'},{'<','>'}};
    private static final int MARKER_PAIR_OPEN_LEN[] = {2,1,1}; // {{  vs {  vs <

    /**
     * 用行内标记语法创建多段拼接文本。
     * <p>
     * 支持三种语法，可混用：
     * <pre>
     * ShanhaiText.inline("物质不拆解（{rainbow}彩虹{/}），也不凭空产生（<lava>lava</>）")
     * ShanhaiText.inline("默认{{fire}}火焰{{/}}又默认")
     * </pre>
     *
     * @param input 带 {style}text{/} / &lt;style&gt;text&lt;/&gt; / {{style}}text{{/}} 标记的文本
     * @return 拼接后的 Component
     */
    public static Component inline(String input) {
        if (input == null || input.isEmpty()) return Component.literal("");
        MutableComponent result = Component.literal("");
        int i = 0, len = input.length();
        while (i < len) {
            // 找到最早的开标记
            int bestIdx = -1, bestPair = -1;
            for (int p = 0; p < 3; p++) {
                String openStr = Character.toString(MARKER_PAIRS[p][0]);
                if (MARKER_PAIR_OPEN_LEN[p] == 2) openStr = "{{";
                int idx = input.indexOf(openStr, i);
                if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
                    bestIdx = idx; bestPair = p;
                }
            }
            if (bestIdx < 0) {
                appendRaw(result, input.substring(i));
                break;
            }
            // 标记前的内容
            if (bestIdx > i) {
                appendRaw(result, input.substring(i, bestIdx));
            }
            int openLen = MARKER_PAIR_OPEN_LEN[bestPair];
            char closeChar = MARKER_PAIRS[bestPair][1];
            int closeLen = (bestPair == 0) ? 2 : 1; // }} vs } vs >
            // 找闭标记
            int markEnd = indexOfClose(input, bestIdx + openLen, closeChar, closeLen);
            if (markEnd < 0) {
                appendRaw(result, input.substring(bestIdx));
                break;
            }
            String styleName = input.substring(bestIdx + openLen, markEnd).trim();
            if (styleName.isEmpty() || styleName.equals("/")) {
                i = markEnd + closeLen;
                continue;
            }
            // 找闭合标记 {/} / </> / {{/}}，支持嵌套 {style}...{/}
            String closeTag = (bestPair == 0) ? "{{/}}" : (bestPair == 1 ? "{/}" : "</>");
            int closeStart = findMatchingClose(input, markEnd + closeLen, closeTag, bestPair);
            String content;
            if (closeStart < 0) {
                content = input.substring(markEnd + closeLen);
                i = len;
            } else {
                content = input.substring(markEnd + closeLen, closeStart);
                i = closeStart + closeTag.length();
            }
            result.append(DShanhaiTextUtil.createStyled(content, styleName));
        }
        return result;
    }

    /**
     * 将无 FCS 标记的原始文本追加到 result。
     * 含 § 码时走 fromLegacy 解析原版格式化，否则走 createStyled("ultimate")。
     */
    private static void appendRaw(MutableComponent result, String text) {
        if (text == null || text.isEmpty()) return;
        if (text.indexOf('§') >= 0 || text.indexOf('\u00A7') >= 0) {
            result.append(DShanhaiTextUtil.fromLegacy(text));
        } else {
            result.append(DShanhaiTextUtil.createStyled(text, "ultimate"));
        }
    }

    private static int indexOfClose(String s, int from, char closeChar, int closeLen) {
        if (closeLen == 2) {
            String cs = String.valueOf(closeChar) + closeChar;
            return s.indexOf(cs, from);
        }
        return s.indexOf(closeChar, from);
    }

    /**
     * 在 input 中从 start 开始找到与 style opener 匹配的 closeTag，
     * 跳过中间嵌套的 {style}...{/} / {{style}}...{{/}} / &lt;style&gt;...&lt;/&gt; 块。
     */
    private static int findMatchingClose(String input, int start, String closeTag, int pair) {
        char openChar = MARKER_PAIRS[pair][0];
        char closeChar = MARKER_PAIRS[pair][1];
        int openLen = MARKER_PAIR_OPEN_LEN[pair];
        int closeLen = (pair == 0) ? 2 : 1;
        int depth = 1;
        int pos = start;
        int len = input.length();
        while (pos < len && depth > 0) {
            int nextOpen = input.indexOf(openChar, pos);
            int nextClose = input.indexOf(closeTag, pos);

            if (nextClose < 0) break;
            if (nextOpen >= 0 && nextOpen < nextClose) {
                // 验证是否为合法的 style 标记：提取 {xxx} 中的 xxx 检查是否为已知 style 名或 /
                int styleNameEnd = input.indexOf(closeChar, nextOpen + openLen);
                if (styleNameEnd < 0) { pos = nextOpen + 1; continue; }
                String candidate = input.substring(nextOpen + openLen, styleNameEnd).trim();
                int tagEnd = styleNameEnd + ((pair == 0) ? 2 : 1); // {{name}} vs {name} vs <name>
                // 只有已知 style 名或 / 才当做嵌套标记
                if (!candidate.equals("/") && DShanhaiStyleRegistry.get(candidate) == null) {
                    pos = tagEnd;
                    continue;
                }
                depth++;
                pos = tagEnd;
            } else {
                depth--;
                pos = nextClose + closeTag.length();
            }
        }
        return (depth == 0) ? (pos - closeTag.length()) : -1;
    }

}
