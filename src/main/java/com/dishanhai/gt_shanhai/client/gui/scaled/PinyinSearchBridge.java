package com.dishanhai.gt_shanhai.client.gui.scaled;

import java.lang.reflect.Method;

/**
 * 拼音搜索桥接（山海署名）。软依赖 <b>Just Enough Characters</b>（modid: jecharacters）。
 *
 * <p>通过反射调用 {@code me.towdium.jecharacters.utils.Match.contains(source, pattern)}
 * 做中文拼音（全拼 / 首字母 / 带声调）子串匹配。该模组的 {@code Match.context}
 * 在静态块内自动初始化（PinIn + accelerate 缓存），开箱即用。</p>
 *
 * <p>若运行环境未装 JECharacters（别的整合包），反射查找失败，{@link #available} 置 false，
 * {@link #contains} 恒返回 false —— 调用方（{@link AdvancedSearchUtil}）据此优雅降级到纯子串搜索，
 * 不影响原有中文/ID/英文搜索。</p>
 *
 * <p>Method 句柄一次性解析并缓存，高频搜索无反射开销。</p>
 */
public final class PinyinSearchBridge {

    private static final Method MATCH_CONTAINS;
    private static final boolean AVAILABLE;

    static {
        Method m = null;
        try {
            Class<?> matchClass = Class.forName("me.towdium.jecharacters.utils.Match");
            // public static boolean contains(String source, CharSequence pattern)
            m = matchClass.getMethod("contains", String.class, CharSequence.class);
        } catch (Throwable ignored) {
            // 未装 JECharacters 或 API 变动 —— 静默降级
        }
        MATCH_CONTAINS = m;
        AVAILABLE = m != null;
    }

    private PinyinSearchBridge() {
    }

    /** 是否可用（运行环境是否提供 JECharacters）。 */
    public static boolean available() {
        return AVAILABLE;
    }

    /**
     * 拼音子串匹配：{@code source} 中是否包含匹配 {@code pattern}（拼音/首字母/汉字）的片段。
     * 不可用或异常时返回 false（交由调用方降级）。
     */
    public static boolean contains(String source, String pattern) {
        if (!AVAILABLE || source == null || pattern == null || pattern.isBlank()) {
            return false;
        }
        try {
            Object r = MATCH_CONTAINS.invoke(null, source, pattern);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
