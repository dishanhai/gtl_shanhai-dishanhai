package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopScreenResponsiveLayoutSourceTest {

    private static final Path SCREEN = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java");

    @Test
    void shopLayoutShrinksCardsDetailAndControlsForSmallViewports() throws Exception {
        String source = Files.readString(SCREEN);

        assertTrue(source.contains("private int detailW()"),
                "右侧详情列不能继续只用固定 DETAIL_W，低分辨率/大卡片时必须可收缩");
        assertTrue(source.contains("private int searchW()"),
                "搜索框宽度必须跟随详情列有效宽度收缩");
        assertTrue(source.contains("cardWidthLimitForTenColumns()"),
                "商品卡片宽度必须按当前可用宽度压到默认 10 列附近");
        assertTrue(source.contains("cardHeightLimitForTargetRows()"),
                "商品卡片高度必须按当前可用高度压到一屏约 12 行");
        assertTrue(source.contains("Math.min(ClientShopUiSettings.cardWidth(), cardWidthLimitForTenColumns())"),
                "用户设置的卡片宽度只能作为基准，不能在低分辨率下强行撑开布局");
        assertTrue(source.contains("Math.min(ClientShopUiSettings.cardHeight(), cardHeightLimitForTargetRows())"),
                "用户设置的卡片高度只能作为基准，不能在低分辨率下强行撑开布局");
        assertTrue(source.contains("private int topButtonW(int normalWidth)"),
                "顶栏按钮也需要在窄布局下收缩，避免和左右按钮组互相覆盖");

        assertFalse(source.contains("new EditBox(this.font, searchBoxX(), searchBoxY(), SEARCH_W"),
                "搜索框不能再直接使用固定 SEARCH_W");
        assertFalse(source.contains("new AnimatableEditBox(this.font, detailX() + 8, contentTop() + 66, DETAIL_W - 16"),
                "数量输入框不能再直接使用固定 DETAIL_W");
    }
}
