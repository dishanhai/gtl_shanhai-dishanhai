package com.dishanhai.gt_shanhai.common.shop;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletAccountPurchaseKeyTest {

    @Test
    void purchaseStatisticsUseStableEntryIdentity() throws Exception {
        Method keyMethod = null;
        for (Method method : WalletAccountAPI.class.getMethods()) {
            if (method.getName().equals("purchaseKey")
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == ShopEntry.class) {
                keyMethod = method;
                break;
            }
        }
        assertTrue(keyMethod != null, "purchaseKey(ShopEntry) must isolate each shop entry");

        ShopEntry greenhouse = entry("greenhouse-array");
        ShopEntry slaughterhouse = entry("slaughterhouse-array");
        String greenhouseKey = (String) keyMethod.invoke(null, greenhouse);
        String slaughterhouseKey = (String) keyMethod.invoke(null, slaughterhouse);

        assertNotEquals(greenhouseKey, slaughterhouseKey,
                "different stableId entries must not share the purchase counter");
    }

    private static ShopEntry entry(String stableId) {
        return new ShopEntry(
                List.of(ShopEntry.GoodsStack.of(new ResourceLocation("minecraft", "stone"), 1, null)),
                ShopEntry.DEFAULT_CATEGORY,
                new ShopCost(BigInteger.ONE, null, null),
                "",
                -1L,
                null,
                ShopEntry.RewardMode.NONE,
                null,
                false,
                null,
                null,
                null,
                null,
                ShopEntry.RewardMode.RANDOM,
                ShopEntry.TradeMode.BOTH,
                -1L,
                -1L,
                null,
                stableId,
                0,
                -1L,
                -1L);
    }
}
