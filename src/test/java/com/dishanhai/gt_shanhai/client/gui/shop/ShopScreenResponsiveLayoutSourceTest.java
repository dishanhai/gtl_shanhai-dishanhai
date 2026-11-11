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
        assertTrue(source.contains("new AnimatableEditBox(this.font, searchBoxX() + 4, searchBoxY() + 2, SEARCH_W - 8, 8"),
                "搜索框文字在固定玻璃框内留出边距，不应随卡片尺寸/低分辨率缩放");
        assertTrue(source.contains("searchBoxX(), searchBoxY(), SEARCH_W, 12, top + panelHeight - 6"),
                "玻璃外框应保留原本固定的搜索区域尺寸");
        assertTrue(source.contains("searchBox.setBordered(false)"),
                "原版黑底白框不能覆盖玻璃搜索框");
        assertTrue(source.contains("new AnimatableEditBox(this.font, detailX() + 8, contentTop() + 66, DETAIL_W - 16"),
                "购买面板内数量输入框必须维持固定 DETAIL_W 布局");
        assertTrue(source.contains("private int buyTabW() { return BUY_TAB_W; }"),
                "顶部购买页签宽度不应随卡片布局缩放");
        assertTrue(source.contains("private int closeBtnW() { return CLOSE_W; }"),
                "顶部关闭按钮宽度不应随卡片布局缩放");
        assertTrue(source.contains("case CURRENCY -> currencyBtnW();")
                        && source.contains("case BACKPACK -> backpackBtnW();"),
                "原生适配仍使用货币中心和精妙背包的固定按钮尺寸");
        assertTrue(source.contains("button.sync(x, top + 6, width, TOP_BAR_H,")
                        && source.contains("button.getX(), button.getY(), button.getWidth(), button.getHeight()"),
                "原生输入和自绘图标共用同一组固定坐标与尺寸");

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

    @Test
    void commerceControlsUseSharedIcons() throws Exception {
        String source = Files.readString(SCREEN);
        for (String icon : new String[]{"CURRENCY", "REDEEM", "MEMBERSHIP", "BUY", "SELL",
                "SHOPPING_CART", "FAVORITE", "FILTER", "BALANCE"}) {
            assertTrue(source.contains("GraphicIcon." + icon), "商店应使用共享图标 " + icon);
        }
        assertTrue(source.contains("balanceCellStart(barX, balanceLabel())"), "余额变化动画应锚定到新货币格");
        assertTrue(source.contains("balanceCellStart(x, balanceLabel())"), "悬停应命中新货币格");
        assertTrue(source.contains("balanceCellStart(x, label)"), "余额栏应在图标和标签之后绘制货币格");
    }
}
