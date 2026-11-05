package com.dishanhai.gt_shanhai.integration.enhancedcore;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/** 山海：可選雲端橋接不得把模組 ID 當成 API 能力保證。 */
class EnhancedCoreCloudApiTest {

    private static boolean supports(boolean installed, Predicate<Object> methods) {
        Class<?> api = assertDoesNotThrow(() -> Class.forName(
                "com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCoreCloudApi"));
        Method probe = assertDoesNotThrow(() -> api.getMethod("supports", boolean.class, Predicate.class));
        return (boolean) assertDoesNotThrow(() -> probe.invoke(null, installed, methods));
    }

    @Test
    void absentModNeverProbesOrLoadsItsClasses() {
        AtomicInteger probes = new AtomicInteger();
        assertFalse(supports(false, method -> { probes.incrementAndGet(); return true; }));
        assertEquals(0, probes.get());
    }

    @Test
    void installedOldModWithMissingApiIsRejected() {
        assertFalse(supports(true, method -> false));
    }

    @Test
    void completeApiIsAccepted() {
        AtomicInteger probes = new AtomicInteger();
        assertTrue(supports(true, method -> { probes.incrementAndGet(); return true; }));
        assertTrue(probes.get() >= 4, "必須檢查多個必要方法而非單一類別");
    }

    @Test
    void oneMissingMethodDisablesTheWholeBridge() {
        AtomicInteger probes = new AtomicInteger();
        assertFalse(supports(true, method -> probes.incrementAndGet() != 2));
        assertEquals(2, probes.get());
    }

    @Test
    void unavailableDependencyFailsClosed() {
        assertFalse(supports(true, method -> { throw new NoClassDefFoundError("missing API dependency"); }));
    }
}
