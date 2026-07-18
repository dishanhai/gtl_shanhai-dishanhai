package com.dishanhai.gt_shanhai.common.machine.primordial;

import com.dishanhai.gt_shanhai.api.machine.primordial.IPrimordialOutputMultiplierModule;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialOmegaEngineOutputMultiplierTest {

    @Test
    void emptyModulesDefaultToOne() {
        var cache = new PrimordialOmegaEngineMachine.OutputMultiplierCache();

        assertEquals(1, cache.get(1L, List.of()));
    }

    @Test
    void ordinaryObjectsAreIgnored() {
        var cache = new PrimordialOmegaEngineMachine.OutputMultiplierCache();

        assertEquals(10, cache.get(1L, List.of(new Object(), module(10), new Object())));
    }

    @Test
    void highestMultiplierWinsWithoutStacking() {
        var cache = new PrimordialOmegaEngineMachine.OutputMultiplierCache();

        assertEquals(1000, cache.get(1L, List.of(module(10), module(100), module(1000))));
    }

    @Test
    void moduleMultipliersAreClampedToHostRange() {
        assertAll(
                () -> assertEquals(1, new PrimordialOmegaEngineMachine.OutputMultiplierCache()
                        .get(1L, List.of(module(0)))),
                () -> assertEquals(1, new PrimordialOmegaEngineMachine.OutputMultiplierCache()
                        .get(1L, List.of(module(-10)))),
                () -> assertEquals(1000, new PrimordialOmegaEngineMachine.OutputMultiplierCache()
                        .get(1L, List.of(module(1001)))));
    }

    @Test
    void sameTickReusesCachedMultiplier() {
        var cache = new PrimordialOmegaEngineMachine.OutputMultiplierCache();

        assertEquals(10, cache.get(1L, List.of(module(10))));
        assertEquals(10, cache.get(1L, List.of(module(1000))));
    }

    @Test
    void invalidationRecalculatesWithinSameTick() {
        var cache = new PrimordialOmegaEngineMachine.OutputMultiplierCache();
        assertEquals(10, cache.get(1L, List.of(module(10))));

        cache.invalidate();

        assertEquals(100, cache.get(1L, List.of(module(100))));
    }

    @Test
    void newTickRecalculatesMultiplier() {
        var cache = new PrimordialOmegaEngineMachine.OutputMultiplierCache();

        assertEquals(10, cache.get(1L, List.of(module(10))));
        assertEquals(100, cache.get(2L, List.of(module(100))));
    }

    @Test
    void invalidationCannotBeOverwrittenByInProgressCalculation() throws Exception {
        var cache = new PrimordialOmegaEngineMachine.OutputMultiplierCache();
        var calculationStarted = new CountDownLatch(1);
        var releaseCalculation = new CountDownLatch(1);
        List<Object> modules = new ArrayList<>();
        modules.add(new BlockingModule(10, calculationStarted, releaseCalculation));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> calculation = executor.submit(() -> cache.get(1L, modules));
            assertTrue(calculationStarted.await(5, TimeUnit.SECONDS));

            synchronized (cache) {
                modules.clear();
                modules.add(module(100));
                cache.invalidate();
            }
            releaseCalculation.countDown();

            assertEquals(100, calculation.get(5, TimeUnit.SECONDS));
            assertEquals(100, cache.get(1L, modules));
        } finally {
            releaseCalculation.countDown();
            executor.shutdownNow();
        }
    }

    private static IPrimordialOutputMultiplierModule module(int multiplier) {
        return new StubModule(multiplier);
    }

    private record StubModule(int multiplier) implements IPrimordialOutputMultiplierModule {

        @Override
        public int getCurrentOutputMultiplier() {
            return multiplier;
        }
    }

    private record BlockingModule(int multiplier, CountDownLatch started, CountDownLatch release)
            implements IPrimordialOutputMultiplierModule {

        @Override
        public int getCurrentOutputMultiplier() {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("等待释放倍率计算超时");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("倍率计算线程被中断", exception);
            }
            return multiplier;
        }
    }
}
