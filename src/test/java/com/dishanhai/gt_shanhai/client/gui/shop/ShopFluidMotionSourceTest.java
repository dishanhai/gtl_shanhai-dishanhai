package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopFluidMotionSourceTest {

    @Test
    void shopFamilyKeepsOneMotionChannel() throws Exception {
        String shop = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopScreen.java"));
        String currency = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/CurrencyAtmScreen.java"));
        String member = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ShopMembershipScreen.java"));
        String exchange = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/ExchangeScreen.java"));
        String util = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/gui/scaled/GuiRenderUtil.java"));

        assertAll(
                () -> assertFalse(shop.contains("cardSwitchAtMs")),
                () -> assertFalse(currency.contains("detailSwitchAtMs")),
                () -> assertTrue(shop.contains("screenOpenAtMs")),
                () -> assertTrue(shop.contains("GuiRenderUtil.slideInX")),
                () -> assertTrue(shop.contains("GROUP_SLIDE_EXTRA")),
                () -> assertTrue(shop.contains("FLASH_ENTER_FROM")),
                () -> assertTrue(member.contains("GuiRenderUtil.slideInX")),
                () -> assertTrue(exchange.contains("screenOpenAtMs")),
                () -> assertFalse(shop.contains("dampingFraction")),
                () -> assertFalse(shop.contains("new RenderTarget")),
                () -> assertTrue(shop.contains("ShopGaussBlur")),
                () -> assertTrue(currency.contains("withPopScale")),
                // 商品卡片 → 详情面板：一条几何通道 + 源卡磨砂/残影；右侧面板的高斯后处理走 ShopGaussBlur
                () -> assertTrue(shop.contains("beginCardFlight")),
                () -> assertTrue(shop.contains("renderCardFlight")),
                () -> assertTrue(shop.contains("GuiRenderUtil.FluidSpring")),
                () -> assertTrue(shop.contains("cardFlightTrails")),
                () -> assertTrue(shop.contains("drawSourceCardBlur")),
                () -> assertTrue(shop.contains("0x88C6D0DA")),
                () -> assertTrue(shop.contains("panelBlur")),
                () -> assertTrue(shop.contains("PANEL_BLUR_DELAY_MS = 32f")),
                () -> assertTrue(shop.contains("PANEL_BLUR_IN_MS = 48f")),
                () -> assertTrue(shop.contains("flightResponseForDistance")),
                () -> assertFalse(shop.contains("cardFlightEntryKey")),
                () -> assertTrue(util.contains("class FluidSpring")),
                () -> assertTrue(util.contains("flightResponseForDistance")),
                () -> assertTrue(util.contains("RESPONSE_S = 0.22")),
                () -> assertTrue(util.contains("DAMPING_FRACTION = 0.92")));
    }
}
