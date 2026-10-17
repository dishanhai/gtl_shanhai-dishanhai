package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyAtmGuideSourceTest {

    @Test
    void currencyCenterExplainsDepositExchangeAndSpark() throws Exception {
        String screen = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/client/gui/shop/CurrencyAtmScreen.java"));
        assertTrue(screen.contains("怎么用货币中心"), "未选货币时详情应写出使用步骤");
        assertTrue(screen.contains("提交全部：背包里的这种币进钱包"), "底部提示应说明提交全部");
        assertTrue(screen.contains("快速售出：可计价的币全部换星火"), "底部提示应说明快速售出");
        assertTrue(screen.contains("这种是特殊货币，不能按币值兑换"), "特殊货币应改走兑换中心提示");
        assertTrue(screen.contains("AE 模式在商店顶栏开关"), "应说明 AE 模式不在本页切换");
        assertTrue(screen.contains("还没有货币"), "空清单应提示检查配置");
    }
}
