package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class GuixuPowerBudgetTest {
    private static BigInteger bi(long n) { return BigInteger.valueOf(n); }

    @Test void powerGrowthBalancesParallelAndSpeed() {
        for (int scale : new int[]{1, 4, 16}) {
            BigInteger power = bi(100L * scale);
            int ticks = GuixuPowerBudget.duration(power, bi(100), 200, 1);
            long[] amounts = GuixuPowerBudget.allocate(power, ticks,
                    new BigInteger[]{bi(20_000)}, new long[]{1000}, bi(1_000_000), 1000);
            assertEquals(200 / (int) Math.sqrt(scale), ticks);
            assertEquals((long) Math.sqrt(scale), amounts[0]);
        }
    }

    @Test void minimumDurationLeavesFurtherGrowthForParallel() {
        assertEquals(20, GuixuPowerBudget.duration(bi(100), bi(100), 5, 20));
        assertEquals(20, GuixuPowerBudget.duration(bi(10_000), bi(100), 200, 20));
        assertEquals(20, GuixuPowerBudget.duration(bi(1_000_000), bi(100), 200, 20));
        assertArrayEquals(new long[]{1000}, GuixuPowerBudget.allocate(bi(1_000_000), 20,
                new BigInteger[]{bi(20_000)}, new long[]{2000}, null, 2000));
    }

    @Test void morePowerNeverReducesParallelSpeedOrSingleRecipeThroughput() {
        long previousParallel = 0;
        int previousTicks = Integer.MAX_VALUE;
        double previousRate = 0;
        for (int ratio = 1; ratio <= 1000; ratio++) {
            BigInteger power = bi(100L * ratio);
            int ticks = GuixuPowerBudget.duration(power, bi(100), 200, 7);
            long limit = GuixuPowerBudget.parallelLimit(power, bi(100), 200, 7, 100_000);
            long parallel = GuixuPowerBudget.allocate(power, ticks, new BigInteger[]{bi(20_000)},
                    new long[]{100_000}, null, limit)[0];
            double rate = (double) parallel / ticks;
            assertTrue(parallel >= previousParallel, "parallel at power ratio " + ratio);
            assertTrue(ticks <= previousTicks, "speed at power ratio " + ratio);
            assertTrue(rate >= previousRate, "throughput at power ratio " + ratio);
            previousParallel = parallel;
            previousTicks = ticks;
            previousRate = rate;
        }
    }

    @Test void sharedAllocationAlwaysFitsPowerStoredEnergyAndGlobalLimits() {
        var random = new java.util.Random(20261025L);
        for (int sample = 0; sample < 500; sample++) {
            BigInteger power = bi(1 + random.nextInt(100_000));
            int ticks = 1 + random.nextInt(200);
            BigInteger stored = bi(random.nextInt(10_000_000));
            long limit = random.nextInt(100_000);
            BigInteger[] costs = new BigInteger[1 + random.nextInt(8)];
            long[] wants = new long[costs.length];
            for (int i = 0; i < costs.length; i++) {
                costs[i] = bi(1 + random.nextInt(10_000));
                wants[i] = random.nextInt(10_000);
            }
            long[] amounts = GuixuPowerBudget.allocate(power, ticks, costs, wants, stored, limit);
            BigInteger consumed = BigInteger.ZERO;
            long total = 0;
            for (int i = 0; i < amounts.length; i++) {
                assertTrue(amounts[i] >= 0 && amounts[i] <= wants[i]);
                consumed = consumed.add(costs[i].multiply(bi(amounts[i])));
                total += amounts[i];
            }
            assertTrue(total <= limit);
            assertTrue(GuixuPowerBudget.roundForTicks(consumed, ticks).compareTo(stored) <= 0);
            assertTrue(consumed.compareTo(power.multiply(bi(ticks))) <= 0);
        }
    }

    @Test void allRecipesShareOneBudgetAndOneParallelLimit() {
        long[] amounts = GuixuPowerBudget.allocate(bi(100), 20,
                new BigInteger[]{bi(500), bi(1000)}, new long[]{100, 100}, null, 100);
        assertArrayEquals(new long[]{2, 1}, amounts);
        assertTrue(bi(500).multiply(bi(amounts[0])).add(bi(1000).multiply(bi(amounts[1])))
                .compareTo(bi(2000)) <= 0);
        assertArrayEquals(new long[]{2, 1}, GuixuPowerBudget.allocate(bi(100_000), 20,
                new BigInteger[]{bi(1), bi(1)}, new long[]{100, 100}, null, 3));
    }

    @Test void missingHatchesOrInsufficientStoredEnergyNeverGrantFreeParallel() {
        assertArrayEquals(new long[]{0}, GuixuPowerBudget.allocate(BigInteger.ZERO, 20,
                new BigInteger[]{bi(1)}, new long[]{100}, null, 100));
        assertArrayEquals(new long[]{0}, GuixuPowerBudget.allocate(bi(1000), 20,
                new BigInteger[]{bi(1000)}, new long[]{100}, bi(999), 100));
        assertArrayEquals(new long[]{2}, GuixuPowerBudget.allocate(bi(1000), 20,
                new BigInteger[]{bi(1000)}, new long[]{100}, bi(2500), 100));
    }

    @Test void budgetArithmeticStaysExactBeyondLongMaxValue() {
        BigInteger huge = bi(Long.MAX_VALUE).multiply(bi(Long.MAX_VALUE));
        assertEquals(1, GuixuPowerBudget.duration(huge, BigInteger.ONE, Integer.MAX_VALUE, 1));
        assertArrayEquals(new long[]{Long.MAX_VALUE}, GuixuPowerBudget.allocate(huge, 1,
                new BigInteger[]{bi(Long.MAX_VALUE)}, new long[]{Long.MAX_VALUE}, null, Long.MAX_VALUE));
    }

    @Test void roundingNeverDiscountsConsumptionOrAmplifiesGeneration() {
        assertEquals(bi(120), GuixuPowerBudget.roundForTicks(bi(101), 20));
        assertEquals(bi(-100), GuixuPowerBudget.roundForTicks(bi(-101), 20));
        assertEquals(BigInteger.ZERO, GuixuPowerBudget.roundForTicks(BigInteger.ZERO, 20));
    }
}
