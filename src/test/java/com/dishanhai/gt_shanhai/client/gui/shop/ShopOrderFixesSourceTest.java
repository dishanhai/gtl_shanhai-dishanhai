package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 商店重開保留選取/搜索、下單頁收藏缺失、樣板可下單校驗。只核對源碼契約。 */
class ShopOrderFixesSourceTest {

    @Test
    void remembersSelectionAndSearchBehindClientSetting() throws Exception {
        String settings = read("client/shop/ClientShopUiSettings.java");
        String screen = read("client/gui/shop/ShopSettingsScreen.java");
        String shop = read("client/gui/shop/ShopScreen.java");

        assertTrue(settings.contains("private static boolean rememberShopView = true"));
        assertTrue(settings.contains("rememberShopView"));
        assertTrue(settings.contains("shopSearch"));
        assertTrue(settings.contains("shopSelectedStableId"));
        assertTrue(screen.contains("记住选取与搜索"));
        assertTrue(screen.contains("ClientShopUiSettings.setRememberShopView"));
        assertTrue(screen.contains("parent.onRememberShopViewSaved"));
        assertTrue(shop.contains("ClientShopUiSettings.shopSearch()"));
        assertTrue(shop.contains("restoreRememberedSelection"));
        assertTrue(shop.contains("ClientShopUiSettings.setShopView(currentSearch(), currentStableId())"));
    }

    @Test
    void orderPageBookmarksMissingCostsLikeGtlCore() throws Exception {
        String shop = read("client/gui/shop/ShopScreen.java");
        String bridge = read("jei/JeiBookmarkBridge.java");

        assertTrue(shop.contains("收藏缺失"));
        assertTrue(shop.contains("JeiBookmarkBridge.addMissing"));
        assertTrue(shop.contains("showFavoriteMissingBtn = mode == Mode.BUY && costShortfall"));
        assertTrue(bridge.contains("public static MissingBookmarkResult addMissing"));
        assertTrue(bridge.contains("MissingBookmarkStatus.ALREADY"));
        assertTrue(bridge.contains("getMethod(\"add\", bookmarkType)"));
    }

    @Test
    void costPreviewAsksWhetherAeHasAPattern() throws Exception {
        String purchase = read("common/shop/ShopPurchase.java");
        String packet = read("network/ShopCostPreviewPacket.java");
        String preview = read("client/shop/ClientCostPreview.java");
        String shop = read("client/gui/shop/ShopScreen.java");

        assertTrue(purchase.contains("getCraftingFor(key)"));
        assertTrue(purchase.contains("只查樣板，不計算合成") || purchase.contains("不跑合成计算"));
        assertTrue(!purchase.contains("beginCraftingCalculation"));
        assertTrue(packet.contains("coinOrder"));
        assertTrue(packet.contains("itemOrder"));
        assertTrue(packet.contains("fluidOrder"));
        assertTrue(preview.contains("int itemOrder("));
        assertTrue(preview.contains("int fluidOrder("));
        assertTrue(shop.contains("可下单: 网络里有样板"));
        assertTrue(shop.contains("不可下单: 网络里没有样板"));
    }

    private static String read(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai").resolve(relative));
    }
}
