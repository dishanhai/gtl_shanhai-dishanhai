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
    void smallViewportsOnlyShrinkProductCards() throws Exception {
        String source = Files.readString(SCREEN);

        assertTrue(source.contains("cardWidthLimitForTenColumns()"),
                "商品卡片宽度必须按当前可用宽度压到默认 10 列附近");
        assertTrue(source.contains("cardHeightLimitForTargetRows()"),
                "商品卡片高度必须按当前可用高度压到一屏约 12 行");
        assertTrue(source.contains("Math.min(ClientShopUiSettings.cardWidth(), cardWidthLimitForTenColumns())"),
                "用户设置的卡片宽度只能作为基准，不能在低分辨率下强行撑开布局");
        assertTrue(source.contains("Math.min(ClientShopUiSettings.cardHeight(), cardHeightLimitForTargetRows())"),
                "用户设置的卡片高度只能作为基准，不能在低分辨率下强行撑开布局");

        assertTrue(source.contains("left + panelWidth - 2 - DETAIL_W - 6"),
                "商品网格只能在固定购买面板左侧自行压缩，不能通过缩小购买面板换空间");
        assertTrue(source.contains("new EditBox(this.font, searchBoxX(), searchBoxY(), SEARCH_W"),
                "搜索框属于上方控件，不应随卡片尺寸/低分辨率缩放");
        assertTrue(source.contains("new AnimatableEditBox(this.font, detailX() + 8, contentTop() + 66, DETAIL_W - 16"),
                "购买面板内数量输入框必须维持固定 DETAIL_W 布局");
        assertTrue(source.contains("private int buyTabW() { return BUY_TAB_W; }"),
                "顶部购买页签宽度不应随卡片布局缩放");
        assertTrue(source.contains("private int closeBtnW() { return CLOSE_W; }"),
                "顶部关闭按钮宽度不应随卡片布局缩放");
        assertTrue(source.contains("drawButton(g, currencyBtnX(), top + 6, currencyBtnW(), TOP_BAR_H, \"§6货币中心\""),
                "顶部选择控件不应切换成紧凑短标签");
        assertTrue(source.contains("drawButton(g, backpackBtnX(), top + 6, backpackBtnW(), TOP_BAR_H,"),
                "精妙背包按钮仍应使用原本固定控件");

        assertFalse(source.contains("private int detailW()"),
                "购买/详情面板不应再有动态宽度");
        assertFalse(source.contains("private int searchW()"),
                "搜索框不应跟随购买面板或卡片缩放");
        assertFalse(source.contains("private int topButtonW("),
                "顶部选择控件不应随低分辨率缩放");
        assertFalse(source.contains("private boolean compactTopBar()"),
                "顶部选择控件不应进入紧凑标签模式");
        assertFalse(source.contains("responsiveLayoutPermille"),
                "解析度适配不应再是整屏控件的统一缩放比例");
        assertFalse(source.contains("compact ?"),
                "顶部控件不应按分辨率切换短文本");
        assertFalse(source.contains("\"§aSDA开\"") || source.contains("\"§8SDA关\""),
                "精妙背包控件不应被缩成 SDA 短标签");
    }
}
