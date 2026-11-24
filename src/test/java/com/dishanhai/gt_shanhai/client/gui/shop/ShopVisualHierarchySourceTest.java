package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.dishanhai.gt_shanhai.client.gui.shop.ShopButtonFeedbackSourceTest.extractBlock;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 山海：从商店主界面的绘制入口验证浏览与交易的视觉层级。 */
class ShopVisualHierarchySourceTest {
    private static final Path SCREEN = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java");

    @Test
    void productDiscountEmphasizesThePriceAndSelectionOwnsTheCardAccent() throws IOException {
        String source = Files.readString(SCREEN);
        String card = source.substring(source.indexOf("private void drawCell("), source.indexOf("private static void renderBox("));

        assertFalse(card.contains("onSale ? RED_DARK"), "折扣不应让整张卡片获得警示描边");
        assertTrue(card.contains("onSale ? RED :"), "卡片仍需标明折扣价格");
        assertTrue(card.contains("if (sel) drawSelectionMarker("), "高对比模式也需要可识别的商品选中标记");
    }

    @Test
    void tradingUsesAnEmphasizedSurfaceWhileKeepingActionFeedback() throws IOException {
        String source = Files.readString(SCREEN);
        String detail = source.substring(source.indexOf("private void drawDetail("), source.indexOf("private void renderCardFlight("));

        assertTrue(detail.contains("theme.tradeButtonFill(btnHover, canTrade)"),
                "可交易状态使用独立的底色，保留服务端判定前的预告");
        assertTrue(detail.contains("confirmLabel(confirmKey,"), "确认按钮仍要显示交易反馈");
    }

    @Test
    void gridHoverUsesTheSameClippedHitTestAsGridClicks() throws IOException {
        String source = Files.readString(SCREEN);
        String grid = extractBlock(source, "private void drawGrid(");
        assertTrue(grid.contains("!modalLayerOpen() && !searchSuggestions.contains(mx, my)"),
                "浮层和搜索联想覆盖的商品应取消悬停高亮");
        assertTrue(grid.contains("entryIndexAt(mx, my)"), "悬停和点击共用裁切后的商品命中");
        assertTrue(grid.contains("idx == hoveredIndex"), "只有命中的商品使用悬停样式");
    }

    @Test
    void ancillaryMouseButtonsCannotSelectProductsOrConfirmTrades() throws IOException {
        String clicked = extractBlock(Files.readString(SCREEN), "protected boolean universalMouseClicked(");
        assertTrue(clicked.contains("if (btn != 0 && btn != 1) return true;"),
                "命中商品时只受理左键和右键");
        String confirm = extractBlock(clicked, "if (btn == 0 && buttonHit(mx, my, cx, btnY, btnW, 20))");
        assertTrue(confirm.contains("performConfirmTrade(false)"), "鼠标确认交易只接受左键");
    }

    @Test
    void openingAProductHandsKeyboardFocusBackFromTheToolbar() throws IOException {
        String source = Files.readString(SCREEN);
        String opened = extractBlock(source, "private void openGridEntry(");
        assertTrue(opened.contains("setFocused(null)"), "打开详情时释放之前的控件焦点");
        assertTrue(opened.contains("selectEntry(entryKey, entry)"));
        assertTrue(extractBlock(source, "protected boolean universalMouseClicked(").contains("openGridEntry("));
        assertTrue(extractBlock(source, "protected boolean universalMouseReleased(").contains("openGridEntry("),
                "编辑模式中未越过拖动阈值的点击也打开详情");
    }
}
