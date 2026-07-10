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
}
