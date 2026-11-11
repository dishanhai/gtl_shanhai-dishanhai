package com.dishanhai.gt_shanhai.client.gui.shop;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：防止昂贵图标解析回到逐帧热路径及可见浮层下的输入穿透。 */
class ShopCardAndOverlaySourceTest {
    private String source() throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java"));
    }
    private String block(String signature) throws Exception {
        String source = source();
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int open = source.indexOf('{', start), depth = 1, end = open + 1;
        while (depth > 0 && end < source.length()) {
            char c = source.charAt(end++);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return source.substring(open, end);
    }

    @Test
    void loadingAndResolvedCardsShareThePriceColumn() throws Exception {
        String cell = block("private void drawCell(");
        String loading = block("private void drawLoadingCell(");
        assertTrue(cell.contains("int numX = cx + layout.priceX()"));
        assertTrue(loading.contains("renderNumberBar(g, cx + layout.priceX()"));
        assertTrue(loading.contains("cx + layout.priceX() + 4"),
                "占位文字需与正式卡片使用同一价格栏，避免加载完成时跳位");
    }

    @Test
    void cardsReuseResolvedIconsAndMetricsAndReloadInvalidatesTheirCache() throws Exception {
        String cell = block("private void drawCell(");
        assertFalse(cell.contains("entry.effectiveIcons()"), "无限盘 NBT 图标解析不应逐帧执行");
        assertFalse(cell.contains("cardCategoryBadge(entry)"), "分类拆分和裁字应走缓存");
        assertFalse(cell.contains("this.font.width("), "价格宽度应缓存");
        assertTrue(block("private CellCache cellCacheFor(").contains("Language.getInstance()"), "资源重载与语言切换须失效");
    }

    @Test
    void veryNarrowCardTextStillHonorsItsPixelBudget() throws Exception {
        assertTrue(block("private String cardText(").contains("plainSubstrByWidth"),
                "共用省略号助手有最小字宽，卡片必须再按真实预算夹住文本");
    }

    @Test
    void closingLayersBlockInputAndContextMenusConsumeWheelEvents() throws Exception {
        assertTrue(block("private boolean modalLayerOpen()").contains("activeOverlay()"));
        assertTrue(block("protected boolean universalMouseClicked(").contains("overlayClosing()"));
        String scroll = block("protected boolean universalMouseScrolled(");
        assertTrue(scroll.contains("ctxMenuOpen || overlayClosing()"));
        assertTrue(block("public boolean charTyped(").contains("modalLayerOpen()"));
        assertTrue(block("protected boolean universalMouseReleased(").contains("activeOverlay() != Overlay.NONE"));
    }

    @Test
    void lockedCategoriesStillAllowTheTopTabGestureToFinishSwitchingAway() throws Exception {
        for (String method : new String[]{"protected boolean universalMouseDragged(", "protected boolean universalMouseReleased("}) {
            String input = block(method);
            assertTrue(input.contains("activeOverlay() != Overlay.NONE"));
            assertTrue(input.contains("stageLockActive && dragTabLevel == -1"),
                    "阶段锁仅挡内容区，顶部分页必须允许在 release 完成切换");
        }
    }

    @Test
    void losingTheSelectedEntryAlsoRemovesLayersThatRequireThatEntry() throws Exception {
        String clear = block("private void clearSelection()");
        assertTrue(clear.contains("closeDescOverlay()") && clear.contains("descMotion.snap(0f)"));
        assertTrue(clear.contains("closeGuideOverlay()") && clear.contains("guideMotion.snap(0f)"));
        assertTrue(clear.contains("draggingDescOverlayScroll = false"));
    }

    @Test
    void tabGesturesFinishOnlyOnTheButtonThatStartedThem() throws Exception {
        assertTrue(block("private void beginTabAction(").contains("dragTabButton = button"));
        String released = block("protected boolean universalMouseReleased(");
        assertTrue(released.contains("if (btn != dragTabButton) return true;"),
                "保留非编辑玩家右键切分类，其他键的 release 不能提前提交或留下追踪");
    }

    @Test
    void openingAndDisablingLayersClearFocusGesturesAndOldSprings() throws Exception {
        String open = block("private void openOverlay(Spring motion)");
        assertTrue(open.contains("cancelUnderlyingInput()"));
        assertTrue(open.contains("closeOtherOverlays(motion)"));
        assertTrue(block("public void onAvailabilityChanged()").contains("resetOverlays()"));
        assertTrue(block("public void removed()").contains("cancelUnderlyingInput()"),
                "切到子画面时也不能保留底层未完成的拖拽与焦点");
        String foreground = block("private void drawShopForeground(");
        int lock = foreground.indexOf("renderStageLockOverlay(");
        int selection = foreground.indexOf("switch (activeOverlay())");
        assertTrue(lock >= 0 && selection > lock);
        assertFalse(foreground.substring(lock, selection).contains("return;"), "购物车须能显示在阶段锁之上");
    }
}
