package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ShopFluidSaleTest {
    @Test void sellsOnlyWholeExtractedUnitsAndReturnsRemainder() {
        AtomicLong returned = new AtomicLong();
        var result = ShopFluidSale.extract(4, 1000, n -> 2500, n -> { returned.set(n); return n; });
        assertEquals(2, result.sold());
        assertEquals(500, returned.get());
        assertEquals(0, result.unreturned());
    }

    @Test void reportsFailedRemainderReturnAndHandlesLargeQuantities() {
        var result = ShopFluidSale.extract(Long.MAX_VALUE / 2, 2, n -> n - 1, n -> 0);
        assertEquals(Long.MAX_VALUE / 2 - 1, result.sold());
        assertEquals(1, result.unreturned());
    }

    @Test void zeroExtractionDoesNotPay() {
        assertEquals(0, ShopFluidSale.extract(3, 1000, n -> 0, n -> n).sold());
    }

    @Test void saleUsesBoundFluidAndSharedLimitBeforeExtraction() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/shop/ShopPurchase.java"));
        String sale = source.substring(source.indexOf("public static long sellBulk("));
        assertTrue(sale.contains("AEFluidKey.of(entry.primaryGoodsFluid())"));
        assertTrue(sale.contains("ShopAeNetwork.availableForPlayer(player, fluidKey)"));
        assertTrue(sale.indexOf("ShopPeriodLimiter.clamp") < sale.indexOf("ShopFluidSale.extract"));
        assertTrue(sale.indexOf("sell = result.sold()") < sale.indexOf("BigInteger sellBig"));
    }
}
