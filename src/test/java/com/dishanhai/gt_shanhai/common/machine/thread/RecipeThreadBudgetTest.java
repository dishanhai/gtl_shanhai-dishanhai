package com.dishanhai.gt_shanhai.common.machine.thread;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Author: dishanhai. Whole-machine arithmetic must remain long and direction-aware. */
class RecipeThreadBudgetTest {
    @Test void parallelCapacitySaturatesWithoutTruncatingToInt() {
        assertEquals(64000000000L, RecipeThreadBudget.multiply(1000000000L, 64L));
        assertEquals(Long.MAX_VALUE, RecipeThreadBudget.multiply(Long.MAX_VALUE, 128L));
        assertEquals(0L, RecipeThreadBudget.multiply(-1L, 128L));
    }

    @Test void directionalPowerUsesAbsoluteReservationOnlyInTheMatchingDirection() {
        assertEquals(BigInteger.valueOf(70L), RecipeThreadBudget.remaining(BigInteger.valueOf(100L),
                java.util.List.of(BigInteger.valueOf(-30L), BigInteger.valueOf(50L)), true));
        assertEquals(BigInteger.valueOf(50L), RecipeThreadBudget.remaining(BigInteger.valueOf(100L),
                java.util.List.of(BigInteger.valueOf(-30L), BigInteger.valueOf(50L)), false));
        assertEquals(BigInteger.ZERO, RecipeThreadBudget.remaining(BigInteger.TEN,
                java.util.List.of(BigInteger.valueOf(-30L)), true));
    }
}
