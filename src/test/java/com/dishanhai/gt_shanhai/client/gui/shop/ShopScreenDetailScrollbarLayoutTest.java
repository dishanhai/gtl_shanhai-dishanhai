package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 详情列滚动条与正文的几何互斥守卫。
 *
 * <p>背景：滚动条轨道是常驻绘制的（没内容可滚也画底色占位），而且画在 {@code disableScissor()} 之后，
 * 属于整个详情列最后落笔的一层。原先 {@code detailScrollbarX()} 取 {@code DETAIL_W - 6 - DETAIL_SCROLLBAR_W}，
 * 起点正好落回正文右缘上一格，于是把「→ 跳转 / 指南详情 / 展开详情 / 补齐全部缺口 / 购买材料」这些满宽按钮
 * 最右一列边框像素整条盖掉——玩家看到的就是按钮右边被竖线切掉一条边。
 *
 * <p>这里不去比对某个魔数，而是把常量从源码里读出来重算一遍版式，这样 DETAIL_W / 内缩量 / 条宽
 * 任意一个被改动导致重新压上正文，都会在构建期直接失败。
 */
class ShopScreenDetailScrollbarLayoutTest {

    private static final Path SCREEN = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java");

    /** 详情列正文的左右内缩：cx = detailX() + 8，宽度统一取 DETAIL_W - 16。 */
    private static final int CONTENT_INSET = 8;

    @Test
    void detailScrollbarNeverPaintsOverTheContentColumn() throws Exception {
        String source = Files.readString(SCREEN);

        int detailW = intConstant(source, "DETAIL_W");
        int barW = intConstant(source, "DETAIL_SCROLLBAR_W");
        int gap = intConstant(source, "DETAIL_SCROLLBAR_GAP");

        // 满宽按钮/文本一律按 DETAIL_W - 16 排版，这是下面右缘推算的前提，前提没了推算就没意义
        assertTrue(source.contains("DETAIL_W - 16"),
                "详情列正文不再用 DETAIL_W - 16 排版，本测试的右缘推算前提已失效，请同步更新 CONTENT_INSET");

        int barStart = scrollbarStartOffset(source, detailW, gap);
        int contentRight = detailW - CONTENT_INSET;   // 正文右缘（不含）
        int panelRightBorder = detailW - 1;           // renderBox 的右边框列

        assertAll(
                () -> assertTrue(barStart >= contentRight,
                        "滚动条起点 " + barStart + " 压在正文右缘 " + contentRight
                                + " 之内，满宽按钮的右边框会被轨道底色盖掉"),
                () -> assertTrue(barStart + barW <= panelRightBorder,
                        "滚动条末端 " + (barStart + barW) + " 越过了面板右边框 " + panelRightBorder),
                () -> assertTrue(gap >= 1, "留白必须为正，否则轨道会紧贴按钮边框，视觉上仍像被切掉"));
    }

    /** 从 {@code detailScrollbarX()} 里解出相对 detailX() 的起点偏移，顺带钉死它是从正文右缘推的而非另起魔数。 */
    private static int scrollbarStartOffset(String source, int detailW, int gap) {
        Matcher m = Pattern.compile(
                "detailScrollbarX\\(\\)\\s*\\{\\s*return\\s+detailX\\(\\)\\s*\\+\\s*DETAIL_W\\s*-\\s*(\\d+)"
                        + "\\s*\\+\\s*DETAIL_SCROLLBAR_GAP\\s*;").matcher(source);
        assertTrue(m.find(),
                "detailScrollbarX() 不再写成「正文右缘 + DETAIL_SCROLLBAR_GAP」的形式，"
                        + "无法确认它给正文让出了留白（回退成 DETAIL_W - 6 - DETAIL_SCROLLBAR_W 会盖掉按钮边框）");
        return detailW - Integer.parseInt(m.group(1)) + gap;
    }

    private static int intConstant(String source, String name) {
        Matcher m = Pattern.compile("static final int " + name + "\\s*=\\s*(\\d+)\\s*;").matcher(source);
        assertTrue(m.find(), "ShopScreen 里找不到常量 " + name);
        return Integer.parseInt(m.group(1));
    }
}
