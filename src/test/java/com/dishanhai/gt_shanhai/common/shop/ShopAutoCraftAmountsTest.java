package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Executable amount regressions without a Minecraft runtime (author: dishanhai). */
class ShopAutoCraftAmountsTest {
    private static BigInteger n(long value) { return BigInteger.valueOf(value); }

    @Test
    void duplicateCostsShareOneStockDeduction() {
        Map<String, BigInteger> demand = new LinkedHashMap<>();
        ShopAutoCraftAmounts.add(demand, "wafer", n(100));
        ShopAutoCraftAmounts.add(demand, "wafer", n(100));
        assertEquals(Map.of("wafer", n(150)),
                ShopAutoCraftAmounts.missing(demand, Map.of("wafer", n(50)), Map.of()));
    }

    @Test
    void tenBillionOrderSubtractsStockAndAlreadyOrderedAmountExactly() {
        assertEquals(Map.of("wafer", n(7_000_000_000L)), ShopAutoCraftAmounts.missing(
                Map.of("wafer", n(10_000_000_000L)), Map.of("wafer", n(2_000_000_000L)),
                Map.of("wafer", n(1_000_000_000L))));
    }

    @Test
    void fullyCoveredInFlightOrderDoesNotSubmitAgain() {
        assertTrue(ShopAutoCraftAmounts.missing(
                Map.of("wafer", n(100)), Map.of("wafer", n(30)),
                Map.of("wafer", n(70))).isEmpty());
    }

    @Test
    void walletCreditAppliesOnlyToCoinCostBeforeMergingPhysicalCost() {
        Map<String, BigInteger> demand = new LinkedHashMap<>();
        ShopAutoCraftAmounts.add(demand, "coin", n(100));
        assertEquals(n(100), ShopAutoCraftAmounts.consume(demand, "coin", n(200)));
        ShopAutoCraftAmounts.add(demand, "coin", n(30));
        assertEquals(Map.of("coin", n(10)),
                ShopAutoCraftAmounts.missing(demand, Map.of("coin", n(20)), Map.of()));
    }

    @Test
    void carriedStockIsConsumedOnceAcrossMergedDemand() {
        Map<String, BigInteger> demand = new LinkedHashMap<>(Map.of("item", n(200)));
        assertEquals(n(50), ShopAutoCraftAmounts.consume(demand, "item", n(50)));
        assertEquals(Map.of("item", n(150)), demand);
        assertEquals(n(150), ShopAutoCraftAmounts.consume(demand, "item", n(500)));
        assertTrue(demand.isEmpty());
    }

    @Test
    void nbtVariantsAndFluidKeysStaySeparate() {
        assertEquals(Map.of("item:nbtA", n(7), "item:nbtB", n(6), "fluid:water", n(1000)),
                ShopAutoCraftAmounts.missing(
                        Map.of("item:nbtA", n(10), "item:nbtB", n(10), "fluid:water", n(2000)),
                        Map.of("item:nbtA", n(3), "item:nbtB", n(4), "fluid:water", n(1000)),
                        Map.of()));
    }

    @Test
    void retainedStockProtectsSatisfiedShopCostsFromBeingUsedAsIngredients() {
        assertEquals(Map.of("a", n(50), "b", n(100)), ShopAutoCraftAmounts.reserve(
                Map.of("a", n(100), "b", n(100)), Map.of("a", n(50), "b", n(200))));
    }

    @Test
    void multiplePlansCannotSpendTheSameReservedMaterial() {
        assertEquals(30L, ShopAutoCraftAmounts.remainingStock(100L, 70L));
        assertEquals(0L, ShopAutoCraftAmounts.remainingStock(100L, 110L));
        assertEquals(0L, ShopAutoCraftAmounts.remainingStock(0L, 70L));
    }

    @Test
    void amountsAboveTwoToThe62AreNotRoundedToLongMax() {
        BigInteger amount = BigInteger.ONE.shiftLeft(62).add(n(123));
        assertEquals(Map.of("item", amount.subtract(n(100))),
                ShopAutoCraftAmounts.missing(Map.of("item", amount), Map.of("item", n(100)), Map.of()));
    }

    @Test
    void mergedAmountsNeverWrapAroundLongMax() {
        Map<String, BigInteger> demand = new LinkedHashMap<>();
        ShopAutoCraftAmounts.add(demand, "item", n(Long.MAX_VALUE));
        ShopAutoCraftAmounts.add(demand, "item", n(Long.MAX_VALUE));
        assertEquals(n(Long.MAX_VALUE).multiply(n(2)), demand.get("item"));
    }

    @Test
    void surplusInventoryDoesNotCreateNegativeOrders() {
        assertTrue(ShopAutoCraftAmounts.missing(
                Map.of("item", n(100)), Map.of("item", n(150)), Map.of("item", n(50))).isEmpty());
    }

    @Test
    void negativeExternalCountsNeverIncreaseAnOrder() {
        assertEquals(Map.of("item", n(100)), ShopAutoCraftAmounts.missing(
                Map.of("item", n(100)), Map.of("item", n(-5)), Map.of("item", n(-10))));
        assertEquals(100L, ShopAutoCraftAmounts.remainingStock(100L, -10L));
    }

    @Test
    void partiallyDeliveredJobCountsOnlyItsRemainingNetworkOutput() {
        assertEquals(60L, ShopAutoCraftAmounts.pendingOutput(100L, 60L, true));
        assertEquals(0L, ShopAutoCraftAmounts.pendingOutput(100L, 60L, false));
        assertEquals(0L, ShopAutoCraftAmounts.pendingOutput(100L, -1L, true));
        assertEquals(100L, ShopAutoCraftAmounts.pendingOutput(100L, 150L, true));
    }
}
