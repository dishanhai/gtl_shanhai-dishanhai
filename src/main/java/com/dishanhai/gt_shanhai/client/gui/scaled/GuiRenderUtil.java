package com.dishanhai.gt_shanhai.client.gui.scaled;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * GUI 绘制小工具（移植自 kinetictweaks {@code GuiRenderUtil}）。纯原版实现。
 */
public class GuiRenderUtil {

    /** 画一个带描边的矩形面板。 */
    public static void drawPanel(GuiGraphics g, int x, int y, int w, int h, int bgColor, int outlineColor) {
        g.fill(x, y, x + w, y + h, bgColor);
        g.renderOutline(x, y, w, h, outlineColor);
    }

    /** 标准浅色面板。 */
    public static void drawStandardPanel(GuiGraphics g, int x, int y, int w, int h) {
        drawPanel(g, x, y, w, h, -14935012, -10066330);
    }

    /** 深色半透明面板（商店网格背景用）。 */
    public static void drawDarkPanel(GuiGraphics g, int x, int y, int w, int h) {
        drawPanel(g, x, y, w, h, -2013265920, -11184811);
    }

    /** 整屏半透明遮罩。 */
    public static void drawShadowOverlay(GuiGraphics g, int screenWidth, int screenHeight) {
        g.fill(0, 0, screenWidth, screenHeight, -1073741824);
    }

    /** 文本超宽时截断加省略号。 */
    public static String trimText(Font font, String text, int width) {
        if (text == null) return "";
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(8, width - font.width("..."))) + "...";
    }

    /** 命中测试。 */
    public static boolean isHovering(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /**
     * 把 {@code &<格式码>} 转成 {@code §<格式码>}：FTBQ 用 {@code &} 代替 {@code §} 写格式化码，两者等价，
     * 兼容玩家在自定义文本（如商店描述）里两种写法混用。非法字符后的 {@code &} 原样保留。
     */
    public static String translateAmpCodes(String s) {
        if (s == null || s.isEmpty() || s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && isFormatChar(s.charAt(i + 1))) {
                sb.append('§').append(s.charAt(i + 1));
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isFormatChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || (c >= 'k' && c <= 'o') || (c >= 'K' && c <= 'O') || c == 'r' || c == 'R';
    }

    /**
     * 弹入动画进度 [0,1]：{@code atMs} 为动画起始时刻（{@code System.currentTimeMillis()}），
     * {@code atMs<=0} 视为"没有进行中的动画"直接返回 1（已完成）。跟 {@link #popScaleAt} 配套使用，
     * 是本模组商店/货币中心系列界面统一的弹入节奏（原属 ShopScreen，抽到这里供多个界面复用）。
     */
    public static float popAnimProgress(long atMs, long durationMs) {
        if (atMs <= 0L) return 1f;
        return Math.min(1f, (System.currentTimeMillis() - atMs) / (float) durationMs);
    }

    /**
     * 以 (cx,cy) 为锚点做一次缩放弹入（0.94→1.0，ease-out：先快后慢，比线性更有"弹"的感觉）。
     * 调用方须自行 pushPose/popPose 包住这次调用和后续的绘制调用。
     */
    public static void popScaleAt(GuiGraphics g, float cx, float cy, float t) {
        float eased = 1f - (1f - t) * (1f - t);
        float scale = 0.94f + 0.06f * eased;
        g.pose().translate(cx, cy, 0);
        g.pose().scale(scale, scale, 1f);
        g.pose().translate(-cx, -cy, 0);
    }
}
