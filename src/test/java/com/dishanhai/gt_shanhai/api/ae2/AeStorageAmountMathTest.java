package com.dishanhai.gt_shanhai.api.ae2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AeStorageAmountMathTest {

    @Test
    void saturatesWhenTwoInfiniteSourcesAreMerged() {
        assertEquals(Long.MAX_VALUE,
                AeStorageAmountMath.saturatedAdd(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void saturatesWhenFiniteAmountIsAddedToAnInfiniteSource() {
        assertEquals(Long.MAX_VALUE,
                AeStorageAmountMath.saturatedAdd(Long.MAX_VALUE, 2_400_000_000_000L));
    }

    @Test
    void invalidNegativeContributionCannotEraseExistingInventory() {
        assertEquals(17L, AeStorageAmountMath.saturatedAdd(17L, -2L));
    }
}
