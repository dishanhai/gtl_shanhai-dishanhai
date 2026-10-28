package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopSellDisplaySourceTest {
    @Test
    void sellDisplayUsesEffectivePriceAndExplainsSharedLimitsAndSources() throws Exception {
        String s = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java"));
        assertTrue(s.contains("selected.getEffectiveCost(memberPct).scaledTo(ShopPurchase.sellRatioPercent())"));
        assertTrue(s.contains("周期额度（买卖共用）"));
        assertTrue(s.contains("本周期已用"));
        assertTrue(s.contains("随身背包持有"));
        assertTrue(s.contains("精妙背包及绑定 AE 网络也可供出售"));
        assertTrue(s.contains("奖励类商品不支持出售"));
        assertTrue(s.contains("mode == Mode.BUY ? drawCostPreview"));
    }
}
