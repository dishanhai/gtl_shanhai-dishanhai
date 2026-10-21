package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopEntry;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.dishanhai.gt_shanhai.client.gui.shop.ShopButtonFeedbackSourceTest.extractBlock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopSingleUseWarnTest {

    private static final String SHOP = "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java";

    @Test
    void warnsFromThreeUpAndNotBelow() {
        assertEquals(3L, ShopEntry.SINGLE_USE_WARN_AMOUNT, "取保守的 3，不是 2");
        assertFalse(ShopEntry.exceedsSingleUseAmount(1));
        assertFalse(ShopEntry.exceedsSingleUseAmount(2));
        assertTrue(ShopEntry.exceedsSingleUseAmount(3));
        assertTrue(ShopEntry.exceedsSingleUseAmount(Long.MAX_VALUE));
    }

    @Test
    void detailBuyWarnsBeforeChoiceScreensAndPacket() throws Exception {
        String trade = extractBlock(Files.readString(Path.of(SHOP)), "private void performConfirmTrade(boolean viaKeyboard, boolean singleUseWarned)");
        int warn = trade.indexOf("ShopSingleUseWarnScreen.open(");
        assertTrue(warn > 0, "详情页购买接入警告框");
        assertTrue(trade.contains("mode == Mode.BUY") && trade.contains("selected.hasSingleUseStorage()")
                && trade.contains("ShopEntry.exceedsSingleUseAmount(amount)"), "只在买入、含 SDA/无限盘、数量达线时弹");
        assertTrue(warn < trade.indexOf("new RewardChoiceScreen(") && warn < trade.indexOf("buttonFeedback.tryBegin("),
                "警告在自选奖励界面与发包之前");
        assertTrue(trade.contains("performConfirmTrade(false, true)"), "确认后带已警告标记重走，不会再弹");
    }

    @Test
    void cartSettleWarnsToo() throws Exception {
        String s = Files.readString(Path.of(SHOP));
        String click = extractBlock(s, "private boolean handleCartOverlayClick(");
        assertTrue(click.contains("cartSingleUseWarnLines(") && click.contains("ShopSingleUseWarnScreen.open("));
        String lines = extractBlock(s, "private List<String> cartSingleUseWarnLines(");
        assertTrue(lines.contains("hasSingleUseStorage()") && lines.contains("exceedsSingleUseAmount(qty)"));
    }
}
