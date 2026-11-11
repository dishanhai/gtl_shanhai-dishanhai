package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：确认、取消和移除共享一次性决策，避免重入及过期方案重复提交。 */
class ShopConfirmationActionsTest {
    @Test
    void confirmationSealsTheChoiceBeforeReturningToTheParent() {
        List<String> events = new ArrayList<>();
        AtomicReference<ShopConfirmationActions> ref = new AtomicReference<>();
        var actions = new ShopConfirmationActions(() -> {
            events.add("return");
            assertTrue(ref.get().finished());
            assertFalse(ref.get().abandon());
            events.add("purchase");
        }, () -> events.add("cancel"), () -> events.add("abandon"));
        ref.set(actions);

        assertTrue(actions.confirm(true));
        assertFalse(actions.confirm(true));
        assertFalse(actions.cancel());
        assertEquals(List.of("return", "purchase"), events);
    }

    @Test
    void disabledConfirmationLeavesCancellationAvailable() {
        List<String> events = new ArrayList<>();
        var actions = new ShopConfirmationActions(() -> events.add("confirm"),
                () -> events.add("cancel"), () -> events.add("abandon"));

        assertFalse(actions.confirm(false));
        assertFalse(actions.finished());
        assertTrue(actions.cancel());
        assertFalse(actions.confirm(true));
        assertFalse(actions.abandon());
        assertEquals(List.of("cancel"), events);
    }

    @Test
    void replacingAnUnfinishedPlanOnlyRunsItsAbandonCallback() {
        List<String> events = new ArrayList<>();
        var actions = new ShopConfirmationActions(() -> events.add("submit"),
                () -> events.add("cancel-and-return"), () -> events.add("cancel-old-plan"));

        assertTrue(actions.abandon());
        assertFalse(actions.abandon());
        assertFalse(actions.cancel());
        assertEquals(List.of("cancel-old-plan"), events);
    }

    @Test
    void callbackFailureDoesNotReopenACommittedDecision() {
        var actions = new ShopConfirmationActions(() -> { throw new IllegalStateException("failed"); },
                () -> fail("cancel after commit"), () -> fail("abandon after commit"));

        assertThrows(IllegalStateException.class, () -> actions.confirm(true));
        assertTrue(actions.finished());
        assertFalse(actions.cancel());
        assertFalse(actions.abandon());
    }
}
