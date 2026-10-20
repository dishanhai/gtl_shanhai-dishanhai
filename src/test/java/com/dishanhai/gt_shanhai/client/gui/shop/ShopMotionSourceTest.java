package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopMotionSourceTest {

    private static final String BASE = "src/main/java/com/dishanhai/gt_shanhai/client/gui/";

    /** 工作区按 core.autocrlf 可能是 CRLF，换行归一后再比。 */
    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path)).replace("\r\n", "\n");
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    @Test
    void overlaysRunOnSpringsAndKeepDrawingWhileClosing() throws Exception {
        String shop = read(BASE + "shop/ShopScreen.java");
        String util = read(BASE + "scaled/GuiRenderUtil.java");

        assertAll(
                // 五个浮层各一根弹簧，旧的“打开时刻”字段全部消失
                () -> assertTrue(shop.contains("Spring cartMotion = new Spring(Spring.PANEL)")),
                () -> assertTrue(shop.contains("Spring descMotion = new Spring(Spring.PANEL)")),
                () -> assertTrue(shop.contains("Spring guideMotion = new Spring(Spring.PANEL)")),
                () -> assertTrue(shop.contains("Spring groupPickerMotion = new Spring(Spring.PANEL)")),
                () -> assertTrue(shop.contains("Spring ctxMotion = new Spring(Spring.PANEL)")),
                () -> assertFalse(shop.contains("cartOverlayOpenAtMs")),
                () -> assertFalse(shop.contains("descOverlayOpenAtMs")),
                () -> assertFalse(shop.contains("guideOverlayOpenAtMs")),
                () -> assertFalse(shop.contains("groupPickerOpenAtMs")),
                () -> assertFalse(shop.contains("ctxMenuOpenAtMs")),
                // 打开 / 关闭统一走 openOverlay / closeOverlay；布尔只在各自的 closeXxx() 里置 false（出现且仅出现一次）
                () -> assertTrue(shop.contains("private void openOverlay(Spring motion)")),
                () -> assertTrue(shop.contains("private void closeOverlay(Spring motion)")),
                () -> assertEquals(1, count(shop, "cartOverlayOpen = false")),
                () -> assertEquals(1, count(shop, "descOverlayOpen = false")),
                () -> assertEquals(1, count(shop, "guideOverlayOpen = false")),
                () -> assertEquals(1, count(shop, "groupPickerOpen = false")),
                () -> assertEquals(1, count(shop, "ctxMenuOpen = false")),
                // 退场中的浮层继续画：渲染看“逻辑开着 或 弹簧还没归零”
                () -> assertTrue(shop.contains("private Overlay activeOverlay()")),
                () -> assertTrue(shop.contains("OVERLAY_VISIBLE_EPS")),
                // 洞、遮罩、缩放读同一个弹簧值
                () -> assertTrue(shop.contains("popHoleRect(")),
                () -> assertTrue(shop.contains("GuiMotion.panelScale(")),
                () -> assertTrue(shop.contains("drawModalScrim(g, content[0], content[1], content[2], content[3], p)")),
                () -> assertTrue(util.contains("public static void withPanelMotion(")),
                // 透明度要在 finally 里复位，否则污染后面的绘制
                () -> assertTrue(util.replaceAll("\\s+", " ")
                        .contains("} finally { g.flush(); g.setColor(1f, 1f, 1f, 1f);"))
        );
    }

    @Test
    void scrollingFollowsASpringWhileHitTestingStaysOnTheShownValue() throws Exception {
        String shop = read(BASE + "shop/ShopScreen.java");

        assertAll(
                () -> assertTrue(shop.contains("Spring gridScrollSpring = new Spring(Spring.SCROLL)")),
                () -> assertTrue(shop.contains("Spring detailScrollSpring = new Spring(Spring.SCROLL)")),
                () -> assertTrue(shop.contains("private void snapGridScroll(int value)")),
                () -> assertTrue(shop.contains("private void snapDetailScroll(int value)")),
                () -> assertTrue(shop.contains("private void advanceGridScroll(long now)")),
                () -> assertTrue(shop.contains("private void advanceDetailScroll(long now)")),
                // 滚轮只改目标
                () -> assertTrue(shop.contains("scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget - (int) d * rowStride()))")),
                () -> assertTrue(shop.contains("detailScrollTarget = clampDetailScroll(detailScrollTarget - (int) d * 18)")),
                // 搜索清空、切分组、收藏筛选这些“上下文变了”的写入点直接到位
                () -> assertTrue(shop.contains("searchBox.setResponder(s -> { snapGridScroll(0); recomputeVisible();")),
                // 预载范围看目标，画和点只看显示值
                () -> assertTrue(shop.contains("gridRange(GRID_LOAD_OVERSCAN, scrollTarget)")),
                () -> assertTrue(shop.contains("gridRange(1, scroll)")),
                () -> assertFalse(shop.contains("scroll = 0;\n        loadingCellAtMs.clear();"))
        );
    }

    @Test
    void hoverAndTabIndicatorUseSprings() throws Exception {
        String shop = read(BASE + "shop/ShopScreen.java");

        assertAll(
                () -> assertTrue(shop.contains("SpringTable<Long> hoverCells = new SpringTable<>(Spring.HOVER)")),
                () -> assertTrue(shop.contains("SpringTable<Long> hoverButtons = new SpringTable<>(Spring.HOVER)")),
                () -> assertTrue(shop.contains("SpringTable<Long> hoverTabs = new SpringTable<>(Spring.HOVER)")),
                () -> assertTrue(shop.contains("GuiMotion.lerpColor(ROW_BG, ROW_HOVER")),
                () -> assertTrue(shop.contains("GuiMotion.lerpColor(BUTTON_BG, BUTTON_HOVER")),
                () -> assertTrue(shop.contains("private void drawTabStrip(")),
                () -> assertTrue(shop.contains("TabIndicator[] tabIndicators")),
                // 换排内容时高亮块直接到位，不从旧排位置滑过来
                () -> assertTrue(shop.contains("sig != signature")),
                // 买卖页签也走同一套高亮块
                () -> assertTrue(shop.contains("drawTabStrip(g, TAB_STRIP_BUYSELL")),
                () -> assertTrue(shop.contains("hoverCells.sweep(")),
                () -> assertTrue(shop.contains("hoverButtons.sweep(")),
                () -> assertTrue(shop.contains("hoverTabs.sweep("))
        );
    }

    @Test
    void cardsStaggerInInsteadOfSlidingTheWholeGrid() throws Exception {
        String shop = read(BASE + "shop/ShopScreen.java");

        assertAll(
                () -> assertTrue(shop.contains("SpringTable<Long> staggerCells = new SpringTable<>(Spring.STAGGER)")),
                () -> assertTrue(shop.contains("private void startStagger(long now)")),
                () -> assertTrue(shop.contains("pendingStagger = true")),
                // 切分组：不再整块横移，SWAP 模糊仍在
                () -> assertFalse(shop.contains("GuiRenderUtil.slideInX")),
                () -> assertFalse(shop.contains("GROUP_SLIDE_EXTRA")),
                () -> assertFalse(shop.contains("gridSlideDir")),
                () -> assertTrue(shop.contains("requestGroupSwitchGauss(")),
                // 每格画完必须复位着色器颜色
                () -> assertTrue(shop.replaceAll("\\s+", " ")
                        .contains("} finally { if (staggered) { g.flush(); g.setColor(1f, 1f, 1f, 1f); g.pose().popPose(); } }")),
                // 错峰期间加载占位的 6px 横移让路
                () -> assertTrue(shop.contains("staggerUntilMs"))
        );
    }

    @Test
    void everyScreenOpensOnTheSameSpring() throws Exception {
        for (String name : new String[]{"ShopScreen", "ExchangeScreen", "CurrencyAtmScreen", "ShopMembershipScreen"}) {
            String src = read(BASE + "shop/" + name + ".java");
            assertTrue(src.contains("Spring openMotion = Spring.opening(Spring.PANEL)"), name);
            assertFalse(src.contains("screenOpenAtMs"), name);
            assertTrue(src.contains("openMotion.update("), name);
        }
        String box = read(BASE + "shop/AnimatableEditBox.java");
        assertTrue(box.contains("GuiRenderUtil.scaleAt("));
        assertTrue(read(BASE + "scaled/GuiRenderUtil.java").contains("public static void scaleAt("));
    }
}
