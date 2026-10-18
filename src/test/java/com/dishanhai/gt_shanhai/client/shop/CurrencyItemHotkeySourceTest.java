package com.dishanhai.gt_shanhai.client.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyItemHotkeySourceTest {

    @Test
    void currencyHotkeyMatchesShopHoldLogicAndSkipsShopJump() throws Exception {
        String currency = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/shop/CurrencyItemHotkey.java"));
        String shop = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/shop/ShopItemHotkey.java"));
        String opener = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreenOpener.java"));
        String screen = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java"));
        String atm = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/CurrencyAtmScreen.java"));
        String init = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/ClientInit.java"));

        assertTrue(currency.contains("InputConstants.isKeyDown("),
                "货币提示必须读取窗口实体按键，和商店热键同一套");
        assertFalse(currency.contains("mapping.isDown("),
                "KeyMapping.isDown() 在 JEI 提示框路径不稳定");
        assertTrue(currency.contains("进入货币中心"), "提示文案应是进入货币中心");
        assertTrue(currency.contains("OPEN_HOVERED_SHOP_ITEM"), "与商店悬停共用同一颗快捷键");
        assertTrue(currency.contains("CurrencyRateConfig.has("), "只对货币中心配置过的币生效");
        assertTrue(currency.contains("requestOpenCurrency("), "按住后应请求打开货币中心");

        assertTrue(shop.contains("CurrencyRateConfig.has(itemId)"),
                "已配置的 GT 币不能再走商店跳转");
        assertTrue(opener.contains("requestOpenCurrency("), "开店入口要能改去货币中心");
        assertTrue(screen.contains("new CurrencyAtmScreen(this, currency)"),
                "商店打开后要进入货币中心并带上币种");
        assertTrue(screen.contains("public void tick()"),
                "要等商店 init 结束后再换到货币中心");
        assertTrue(atm.contains("applyInitialFocus("), "货币中心要选中跳转进来的币");
        assertTrue(init.contains("CurrencyItemHotkey::onItemTooltip"), "要注册提示监听");
        assertTrue(init.contains("CurrencyItemHotkey::onClientTick"), "要注册按住计时");
        assertTrue(init.contains("CurrencyItemHotkey.reset()"), "离线要清掉按住状态");
    }
}
