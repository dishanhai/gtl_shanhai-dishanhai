package com.dishanhai.gt_shanhai.common.machine.primordial;

import com.dishanhai.gt_shanhai.api.machine.primordial.IPrimordialOutputMultiplierModule;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static IPrimordialOutputMultiplierModule module(int multiplier) {
        return new StubModule(multiplier);
    }

    private record StubModule(int multiplier) implements IPrimordialOutputMultiplierModule {

        @Override
        public int getCurrentOutputMultiplier() {
            return multiplier;
        }
    }
}
