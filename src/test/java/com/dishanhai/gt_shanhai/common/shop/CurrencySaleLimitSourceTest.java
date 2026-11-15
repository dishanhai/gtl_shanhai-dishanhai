package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencySaleLimitSourceTest {

    private static final Path SOURCE = Path.of("src/main/java/com/dishanhai/gt_shanhai");

    @Test
    void bothSaleEntrypointsUseTheSameSettlement() throws IOException {
        String source = Files.readString(SOURCE.resolve("common/shop/ShopPurchase.java"));
        int quickSale = source.indexOf("public static QuickSellResult quickSellAllCurrencies");
        int manualSale = source.indexOf("public static DigitalResult toDigitalResult");
        assertTrue(source.substring(quickSale, manualSale).contains("settleCurrencySale("));
        assertTrue(source.substring(manualSale).contains("return settleCurrencySale("));
        int quota = source.indexOf("CurrencySaleLimit.remaining", manualSale);
        int extract = source.indexOf("aeExtractCoin(player, currency", manualSale);
        int convert = source.indexOf("WalletAccountAPI.convertCurrencyToDigital", manualSale);
        int consume = source.indexOf("CurrencySaleLimit.consume(", manualSale);
        assertTrue(quota > manualSale && extract > quota && convert > extract && consume > convert);
    }

    @Test
    void settlementAndDisplayUseTheSameTierQuota() throws IOException {
        String limit = Files.readString(SOURCE.resolve("common/shop/CurrencySaleLimit.java"));
        String wallet = Files.readString(SOURCE.resolve("client/shop/ClientWalletAccount.java"));
        assertTrue(limit.contains("tierLimit(faceValue, DShanhaiConfig.COMMON.shopCurrencySaleLimit.get())"));
        assertTrue(wallet.contains("CurrencySaleLimit.tierLimit(faceValue, currencySaleLimit)"));
    }

    @Test
    void changingTheSalePolicyRequiresActiveEditMode() throws IOException {
        String source = Files.readString(SOURCE.resolve("network/ShopSettingsPacket.java"));
        assertTrue(source.contains("ShopEditPermission.canEditCatalog(player)"));
    }
}
