package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.sh_better_ui.client.gui.display.GraphicIcon;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：图文入口保留语义、空间与旧库下可见的购买回退图标。 */
class ShopToolbarIconsTest {
    @Test
    void cartUsesItsExistingSharedIcon() {
        assertSame(GraphicIcon.SHOPPING_CART, ShopToolbarIcons.shoppingCart(() -> GraphicIcon.SHOPPING_CART));
    }

    @Test
    void oldLibraryStillGetsAVisibleBuyIcon() {
        assertSame(GraphicIcon.BUY, ShopToolbarIcons.shoppingCart(() -> { throw new NoSuchFieldError("SHOPPING_CART"); }));
        assertSame(GraphicIcon.BUY, ShopToolbarIcons.shoppingCart(() -> null));
    }

    @Test
    void widthIncludesTheIconAndPaddingWithoutGrowingPastTheNarrowToolbarBudget() {
        assertEquals(48, ShopToolbarIcons.contentWidth(48, 96, 27));
        assertEquals(72, ShopToolbarIcons.contentWidth(48, 96, 52));
        assertEquals(90, ShopToolbarIcons.contentWidth(64, 90, 70));
        assertEquals(96, ShopToolbarIcons.contentWidth(48, 96, 200));
        int rightGroup = 52 + ShopToolbarIcons.contentWidth(48, 96, 200)
                + ShopToolbarIcons.contentWidth(78, 100, 200) + ShopToolbarIcons.contentWidth(58, 68, 200)
                + ShopToolbarIcons.contentWidth(64, 90, 200) + 60 + 5 * 4;
        assertTrue(700 - 14 - 8 - rightGroup - 6 >= 180, "最窄逻辑画布仍给可滚动左顶栏保留空间");
    }

    @Test
    void fiveToolbarEntriesKeepTheirSharedIconsAndFullCartLabel() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java"));
        for (String mapping : new String[]{"case BACKPACK -> GraphicIcon.SOPHISTICATED_BACKPACKS",
                "case AE -> GraphicIcon.AE2", "case DEPOSIT -> GraphicIcon.PAYMENT", "case CLOSE -> GraphicIcon.CLOSE",
                "case CART -> SHOPPING_CART_ICON"}) assertTrue(source.contains(mapping), mapping);
        assertTrue(source.contains("ShopToolbarIcons.shoppingCart(() -> GraphicIcon.SHOPPING_CART)"));
        assertFalse(source.contains("if (action == ToolbarAction.CART && SHOPPING_CART_ICON != null"),
                "购物车非空也要保留完整按钮名称，数量不能替代文字");
        assertTrue(source.contains("String label = button.getMessage().getString()"), "绘制文字与完整旁白沿用同一标签");
        assertTrue(source.contains("ShopToolbarIcons.contentWidth(CART_BTN_W, 96,"));
    }
}
