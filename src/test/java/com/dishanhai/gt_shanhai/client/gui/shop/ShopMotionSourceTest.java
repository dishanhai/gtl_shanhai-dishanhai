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

    @Test
    void currencyCenterMainInterfaceRunsOnTheSameFramework() throws Exception {
        String atm = read(BASE + "shop/CurrencyAtmScreen.java");
        String wheel = atm.substring(atm.indexOf("protected boolean universalMouseScrolled("),
                atm.indexOf("private void cycleTarget("));
        String button = atm.substring(atm.indexOf("private void drawButton("),
                atm.indexOf("private void armTip("));
        String list = atm.substring(atm.indexOf("private void drawList("),
                atm.indexOf("private void armCurrencyTip("));
        assertAll(
                // 列表平滑滚动：显示值照旧给绘制和命中，滚轮只改目标
                () -> assertTrue(atm.contains("private final Spring listScrollSpring = new Spring(Spring.SCROLL)")),
                () -> assertTrue(atm.contains("private int scrollTarget")),
                () -> assertTrue(atm.contains("private void snapListScroll(int value)")),
                () -> assertTrue(atm.contains("private void advanceListScroll(long now)")),
                () -> assertTrue(wheel.contains("scrollTarget = ")),
                () -> assertFalse(wheel.contains("scroll = Math.max(0")),
                // 初始定位到被悬停的币：直接到位，不滑
                () -> assertTrue(atm.contains("snapListScroll(")),
                // hover 渐变：行与按钮各一张表；按钮 key 不带会变的文案
                () -> assertTrue(atm.contains("SpringTable<ResourceLocation> hoverRows = new SpringTable<>(Spring.HOVER)")),
                () -> assertTrue(atm.contains("SpringTable<Long> hoverButtons = new SpringTable<>(Spring.HOVER)")),
                () -> assertTrue(button.contains("hoverButtons.follow(")),
                () -> assertTrue(button.contains("GuiMotion.lerpColor(")),
                () -> assertFalse(button.contains("hashCode()")),
                () -> assertTrue(list.contains("hoverRows.follow(")),
                // 选中高亮块滑动 + 详情列切币淡入
                () -> assertTrue(atm.contains("private final Spring selectIndicator = new Spring(Spring.INDICATOR)")),
                () -> assertTrue(list.contains("selectIndicator.value()")),
                () -> assertTrue(atm.contains("private final Spring detailFade")),
                () -> assertTrue(atm.contains("detailFade.setTarget(1f")),
                // 开场行错峰
                () -> assertTrue(atm.contains("SpringTable<ResourceLocation> staggerRows = new SpringTable<>(Spring.STAGGER)")),
                () -> assertTrue(atm.contains("private void startRowStagger(long now)")),
                () -> assertTrue(list.contains("staggerRows.valueOr(")),
                // 带透明度的绘制必须先 flush、finally 里还原
                () -> assertTrue(atm.contains("g.setColor(1f, 1f, 1f, 1f)")),
                // 逐帧推进 + 清扫
                () -> assertTrue(atm.contains("advanceListScroll(nowMs)")),
                () -> assertTrue(atm.contains("hoverRows.sweep(nowMs)")),
                () -> assertTrue(atm.contains("hoverButtons.sweep(nowMs)"))
        );
    }
}
