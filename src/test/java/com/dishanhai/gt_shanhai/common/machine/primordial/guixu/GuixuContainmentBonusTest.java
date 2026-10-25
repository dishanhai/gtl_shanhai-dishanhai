package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuContainmentField.Status;
import org.junit.jupiter.api.Test;

import static com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuContainmentBonus.*;
import static org.junit.jupiter.api.Assertions.*;

class GuixuContainmentBonusTest {

    @Test
    void closedFieldGivesNoBonus() {
        assertSame(NONE, of(Status.CLOSED, 100.0F));
        assertFalse(NONE.affectsRecipes());
        assertFalse(NONE.locked());
    }

    @Test
    void containedTiersFollowStability() {
        GuixuContainmentBonus high = of(Status.CONTAINED, 50.0F);
        assertEquals(4L, high.parallelMultiplier());
        assertEquals(0.5D, high.durationMultiplier());
        assertEquals(5, high.extraOutput());
        assertEquals(0.5D, high.energyMultiplier());
        assertEquals(2, high.matterCountMultiplier());
        assertEquals(0.0D, high.swallowChance());
        assertTrue(high.affectsRecipes());

        assertEquals(8L, of(Status.CONTAINED, 49.0F).parallelMultiplier());
        assertEquals(0.25D, of(Status.CONTAINED, 20.0F).durationMultiplier());
        assertEquals(16L, of(Status.CONTAINED, 19.0F).parallelMultiplier());
        assertEquals(0.125D, of(Status.CONTAINED, 1.0F).durationMultiplier());
    }

    @Test
    void hyperstableGetsBaseTierOnly() {
        assertEquals(of(Status.CONTAINED, 100.0F), of(Status.HYPERSTABLE, 100.0F));
    }

    @Test
    void unstableRemovesBuffsAndSwallowsLinearly() {
        GuixuContainmentBonus start = of(Status.UNSTABLE, 0.0F);
        assertEquals(0.0D, start.swallowChance());
        assertEquals(1L, start.parallelMultiplier());
        assertEquals(1, start.matterCountMultiplier());
        assertEquals(0, start.extraOutput());
        assertEquals(0.5D, of(Status.UNSTABLE, -450.0F).swallowChance());
        assertEquals(1.0D, of(Status.UNSTABLE, -900.0F).swallowChance());
        assertTrue(of(Status.UNSTABLE, -450.0F).affectsRecipes());
    }

    @Test
    void lockedBonusIsDistinct() {
        assertTrue(LOCKED.locked());
        assertTrue(LOCKED.affectsRecipes());
        assertNotEquals(NONE, LOCKED);
    }

    @Test
    void scalingHelpersSaturateAndClamp() {
        GuixuContainmentBonus low = of(Status.CONTAINED, 10.0F);
        assertEquals(160L, low.scaleParallel(10L));
        assertEquals(Long.MAX_VALUE, low.scaleParallel(Long.MAX_VALUE / 2));
        assertEquals(2, low.scaleDuration(20));
        assertEquals(1, low.scaleDuration(1));
        assertEquals(20, NONE.scaleDuration(20));
        assertEquals(50L, low.scaleEnergy(100L));
        assertEquals(1L, low.scaleEnergy(1L));
        assertEquals(-100L, low.scaleEnergy(-100L));
        assertEquals(0.5D, low.energyFactor(true));
        assertEquals(1.0D, low.energyFactor(false));
        assertTrue(of(Status.UNSTABLE, -450.0F).shouldSwallow(0.49D));
        assertFalse(of(Status.UNSTABLE, -450.0F).shouldSwallow(0.5D));
        assertFalse(NONE.shouldSwallow(0.0D));
    }
}
