package com.dishanhai.gt_shanhai.common.machine.wave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：回響64透鏡增幅及離線16透鏡降級，槽內數量不參與破壞性截斷。 */
class NyarlathotepEchoBroadcastProfileTest {
    private Object profile(int lenses, boolean online, int tier) throws Exception {
        return Class.forName("com.dishanhai.gt_shanhai.common.machine.wave.NyarlathotepEchoBroadcastProfile")
                .getMethod("calculate", int.class, boolean.class, int.class).invoke(null, lenses, online, tier);
    }

    private Number value(Object profile, String field) throws Exception {
        return (Number) profile.getClass().getMethod(field).invoke(profile);
    }

    @Test void fullLinkedStackReachesFivefoldAndTwelveHundredBlocks() throws Exception {
        Object full = profile(64, true, 9);
        assertEquals(64, value(full, "effectiveLenses").intValue());
        assertEquals(1200, value(full, "radius").intValue());
        assertEquals(5, value(full, "outputMultiplier").intValue());
        assertEquals(5, value(full, "selfFactor").doubleValue());
        assertEquals(100, value(full, "powerLevel").intValue());
    }

    @Test void offlineKeepsStoredStackButOnlySixteenLensesContribute() throws Exception {
        Object offline = profile(64, false, 9), ordinary = profile(16, true, 9);
        assertEquals(ordinary, offline);
        assertEquals(16, value(offline, "effectiveLenses").intValue());
        assertEquals(320, value(offline, "radius").intValue());
        assertEquals(3, value(offline, "outputMultiplier").intValue());
        assertEquals(1, value(offline, "selfFactor").doubleValue());
    }

    @Test void intermediateLensesScaleContinuouslyAndPreserveLegacyBaseline() throws Exception {
        Object empty = profile(0, false, 1);
        assertEquals(32, value(empty, "radius").intValue());
        assertEquals(2, value(empty, "outputMultiplier").intValue());
        Object middle = profile(40, true, 9);
        assertEquals(760, value(middle, "radius").intValue());
        assertEquals(4, value(middle, "outputMultiplier").intValue());
        assertEquals(3, value(middle, "selfFactor").doubleValue());
        int previous = 0;
        for (int lens = 0; lens <= 64; lens++) {
            int radius = value(profile(lens, true, 9), "radius").intValue();
            assertTrue(radius >= previous);
            previous = radius;
        }
    }

    @Test void negativeAndOverflowingInputsRemainBounded() throws Exception {
        assertEquals(profile(0, false, 1), profile(Integer.MIN_VALUE, false, Integer.MIN_VALUE));
        Object full = profile(Integer.MAX_VALUE, true, Integer.MAX_VALUE);
        assertEquals(64, value(full, "effectiveLenses").intValue());
        assertEquals(1200, value(full, "radius").intValue());
        assertEquals(100, value(full, "powerLevel").intValue());
        assertEquals(5, value(full, "selfFactor").doubleValue());
    }
}
