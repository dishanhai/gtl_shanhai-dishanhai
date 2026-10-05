package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyAtmAeQuickSellSourceTest {

    private static final Path PURCHASE = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/common/shop/ShopPurchase.java");
    private static final Path SCREEN = Path.of(
            "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/CurrencyAtmScreen.java");

    @Test
    void quickSellConvertsAllConfiguredCurrenciesAndCanPullAe() throws Exception {
        String source = Files.readString(PURCHASE);

        assertTrue(source.contains("quickSellAllCurrencies"),
                "服务端必须提供快速售出全部货币入口");
        assertTrue(source.contains("CurrencyRateConfig.getCurrencies()"),
                "快速售出必须遍历货币中心配置的全部币种");
        assertTrue(source.contains("aeExtractCoin"),
                "开启 AE 模式时快速售出必须先抽取 AE 内货币");
        assertTrue(source.contains("convertCurrencyToDigital"),
                "快速售出必须按币值转入星火");
    }

    @Test
    void atmShowsQuickSellButtonAndRemovesManualAeSteps() throws Exception {
        String source = Files.readString(SCREEN);

        assertTrue(source.contains("快速售出全部货币"),
                "货币中心必须绘制快速售出全部货币按键");
        assertTrue(source.contains("CurrencyQuickSellPacket"),
                "按键必须发专用快速售出请求");
        assertTrue(source.contains("钱包") && source.contains("AE"),
                "货币中心余额展示必须同时包含钱包与 AE");
        assertTrue(!source.contains("AE·全部"),
                "快速链路不应继续显示 AE·全部手动步骤");
        assertTrue(!source.contains("从 AE 抽取"),
                "快速链路不应继续显示从 AE 抽取手动步骤");
    }
}
