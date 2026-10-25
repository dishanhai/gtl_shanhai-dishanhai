package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuContainmentField.Event;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuContainmentField.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuixuContainmentFieldTest {

    @Test
    void closedFieldOpensWithSeedAndIgnoresSecondSeed() {
        GuixuContainmentField field = new GuixuContainmentField();
        assertEquals(Status.CLOSED, field.status());
        assertEquals(Event.OPENED, field.open(false));
        assertEquals(Status.CONTAINED, field.status());
        assertEquals(100.0F, field.stability());
        assertEquals(Event.NONE, field.open(true));
        assertEquals(Status.CONTAINED, field.status());
    }

    @Test
    void suppliedSpacetimeHoldsStabilityAndDoublesCostEveryThirtySeconds() {
        GuixuContainmentField field = new GuixuContainmentField();
        field.open(false);
        assertEquals(10_000L, field.requiredSpacetime());
        for (int i = 0; i < 29; i++) {
            assertEquals(Event.NONE, field.tickSecond(true));
        }
        assertEquals(1L, field.costModifier());
        assertEquals(29, field.catalysisCounter());
        field.tickSecond(true);
        assertEquals(2L, field.costModifier());
        assertEquals(0, field.catalysisCounter());
        assertEquals(20_000L, field.requiredSpacetime());
        assertEquals(100.0F, field.stability());
    }

    @Test
    void costModifierIsCapped() {
        GuixuContainmentField field = new GuixuContainmentField();
        field.restore(Status.CONTAINED.ordinal(), 100.0F, GuixuContainmentField.MAX_COST_MODIFIER, 29);
        field.tickSecond(true);
        assertEquals(GuixuContainmentField.MAX_COST_MODIFIER, field.costModifier());
    }

    @Test
    void missingSpacetimeDecaysIntoInstabilityThenCollapse() {
        GuixuContainmentField field = new GuixuContainmentField();
        field.open(false);
        for (int i = 0; i < 99; i++) {
            assertEquals(Event.NONE, field.tickSecond(false));
        }
        assertEquals(1.0F, field.stability());
        assertEquals(Event.DESTABILIZED, field.tickSecond(false));
        assertEquals(Status.UNSTABLE, field.status());
        assertEquals(0L, field.requiredSpacetime());
        // 失穩後供應流體也不會停止衰減
        for (int i = 0; i < 899; i++) {
            assertEquals(Event.NONE, field.tickSecond(true));
        }
        assertEquals(-899.0F, field.stability());
        assertEquals(Event.COLLAPSED, field.tickSecond(true));
        assertEquals(Status.CLOSED, field.status());
        assertEquals(100.0F, field.stability());
        assertEquals(1L, field.costModifier());
    }

    @Test
    void collapserShutsDownWithoutCollapseEvent() {
        GuixuContainmentField field = new GuixuContainmentField();
        field.open(false);
        for (int i = 0; i < 30; i++) {
            field.tickSecond(true);
        }
        assertEquals(Event.SHUT_DOWN, field.shutDown());
        assertEquals(Status.CLOSED, field.status());
        assertEquals(1L, field.costModifier());
        assertEquals(0, field.catalysisCounter());
        assertEquals(Event.NONE, field.shutDown());
    }

    @Test
    void hyperstableNeverDecaysOrConsumes() {
        GuixuContainmentField field = new GuixuContainmentField();
        field.open(true);
        assertEquals(Status.HYPERSTABLE, field.status());
        assertEquals(0L, field.requiredSpacetime());
        for (int i = 0; i < 1000; i++) {
            assertEquals(Event.NONE, field.tickSecond(false));
        }
        assertEquals(100.0F, field.stability());
    }

    @Test
    void forceCollapseOnlyAffectsUnstableField() {
        GuixuContainmentField field = new GuixuContainmentField();
        field.open(false);
        assertEquals(Event.NONE, field.forceCollapse());
        field.restore(Status.UNSTABLE.ordinal(), -10.0F, 4L, 0);
        assertEquals(Event.COLLAPSED, field.forceCollapse());
        assertEquals(Status.CLOSED, field.status());
    }

    @Test
    void restoreRejectsInvalidStatus() {
        GuixuContainmentField field = new GuixuContainmentField();
        field.restore(99, 3.0F, 8L, 5);
        assertEquals(Status.CLOSED, field.status());
        assertEquals(100.0F, field.stability());
        assertEquals(1L, field.costModifier());
    }
}
