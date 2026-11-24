package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 山海：联想弹层的选择提示和分页控件从实际绘制及点击入口核对。 */
class ShopSearchSuggestionsSourceTest {
    private static final Path POPUP = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopSearchSuggestions.java");

    @Test
    void selectedRowHasItsOwnMarkerAndMouseHoverOnlyTracksThePointer() throws IOException {
        String source = Files.readString(POPUP);
        String render = source.substring(source.indexOf("void drawPopup("));

        assertTrue(render.contains("ui.rowHover(row.index(), hovered == row.index())"));
        assertTrue(render.contains("if (selected) g.fill("));
        assertTrue(render.contains("highContrast() ? -1"));
    }

    @Test
    void onlyEnabledArrowBoundsChangePageAndFooterWhitespaceIsConsumed() throws IOException {
        String source = Files.readString(POPUP);
        String click = source.substring(source.indexOf("boolean mouseClicked("), source.indexOf("boolean mouseScrolled("));
        String render = source.substring(source.indexOf("void drawPopup("));

        assertFalse(click.contains("width / 3.0"), "页码旁的留白不能触发翻页");
        assertTrue(click.contains("canChangePage(delta) && pageButton(footerY, labelWidth, delta).contains(mx, my)"));
        assertTrue(render.contains("Bounds bounds = pageButton(footerY, labelWidth, delta)"));
        assertTrue(render.contains("enabled && bounds.contains(mx, my)"));
        assertTrue(click.indexOf("return true;") < click.indexOf("int index = rowAt"));
    }
}
