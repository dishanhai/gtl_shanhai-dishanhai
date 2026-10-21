package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopActionOutcomeTest {

    @Test
    void nothingDoneIsAFailure() {
        assertEquals(ShopActionOutcome.FAILED, ShopActionOutcome.classify(5L, 0L));
        assertEquals(ShopActionOutcome.FAILED, ShopActionOutcome.classify(1L, -1L));
    }

    @Test
    void everythingDoneIsASuccess() {
        assertEquals(ShopActionOutcome.SUCCESS, ShopActionOutcome.classify(5L, 5L));
        assertEquals(ShopActionOutcome.SUCCESS, ShopActionOutcome.classify(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(ShopActionOutcome.SUCCESS, ShopActionOutcome.classify(3L, 4L), "多成交也算全部成交");
    }

    @Test
    void anythingInBetweenIsPartial() {
        assertEquals(ShopActionOutcome.PARTIAL, ShopActionOutcome.classify(5L, 1L));
        assertEquals(ShopActionOutcome.PARTIAL, ShopActionOutcome.classify(Long.MAX_VALUE, 64L));
    }
}
