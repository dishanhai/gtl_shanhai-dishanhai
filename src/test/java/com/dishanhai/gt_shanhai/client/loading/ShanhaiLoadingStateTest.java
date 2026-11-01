package com.dishanhai.gt_shanhai.client.loading;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：載入會話、真實計數與取消邊界。 */
class ShanhaiLoadingStateTest {
    @Test void reconnectClearsPreviousWorldCountsAndHistory() {
        var state = new ShanhaiLoadingState();
        state.begin(1000L);
        state.update("recipes", "解析配方", "example:recipe", 12, 100);
        state.finish(2000L);
        state.begin(3000L);
        var view = state.snapshot(3100L, false);
        assertTrue(view.active());
        assertEquals(100L, view.elapsedMs());
        assertTrue(view.tasks().isEmpty());
        assertEquals(1f, view.opacity());
    }

    @Test void unknownTotalsNeverProduceAFabricatedPercentage() {
        var state = new ShanhaiLoadingState();
        state.begin(0);
        state.update("connect", "連線", "等待伺服器", 0, -1);
        var task = state.snapshot(90_000, false).tasks().get(0);
        assertEquals(-1, task.total());
        assertEquals(-1f, task.fraction());
        assertTrue(state.snapshot(90_000, false).active());
    }

    @Test void workFromCancelledSessionCannotUpdateAReconnectedWorld() {
        var state = new ShanhaiLoadingState();
        long old = state.begin(0);
        state.reset();
        state.begin(100);
        state.update(old, "recipes", "配方", "old world", 12, 20);
        assertTrue(state.snapshot(200, false).tasks().isEmpty());
    }

    @Test void knownTotalsAreClampedAndRepeatedUpdatesKeepOneRow() {
        var state = new ShanhaiLoadingState();
        state.begin(0);
        state.update("recipes", "解析配方", "first", -5, 100);
        assertEquals(0, state.snapshot(0, false).tasks().get(0).current());
        state.update("recipes", "解析配方", "second", 500, 100);
        var view = state.snapshot(0, false);
        assertEquals(1, view.tasks().size());
        assertEquals("second", view.tasks().get(0).detail());
        assertEquals(1f, view.tasks().get(0).fraction());
    }

    @Test void resetRejectsLateProgressAndSnapshotsAreImmutable() {
        var state = new ShanhaiLoadingState();
        state.begin(0);
        state.update("recipes", "配方", "", 1, 2);
        var before = state.snapshot(0, false);
        state.reset();
        state.update("recipes", "配方", "late", 2, 2);
        assertFalse(state.snapshot(10, false).active());
        assertTrue(state.snapshot(10, false).tasks().isEmpty());
        assertEquals(1, before.tasks().get(0).current());
        assertThrows(UnsupportedOperationException.class, () -> before.tasks().clear());
    }

    @Test void finishFadesOnceWithoutDelayingReadinessAndReduceMotionIsImmediate() {
        var state = new ShanhaiLoadingState();
        state.begin(0);
        state.finish(1000);
        state.finish(1100);
        assertFalse(state.snapshot(1100, false).active());
        assertTrue(state.snapshot(1100, false).opacity() > 0f);
        assertTrue(state.snapshot(1100, false).opacity() < 1f);
        assertEquals(0f, state.snapshot(1400, false).opacity());
        assertEquals(0f, state.snapshot(1000, true).opacity());
    }

    @Test void detailsWrapWithoutDroppingCharactersEvenInNarrowWindows() {
        String text = "gt_shanhai:long_recipe_identifier/配方載入";
        var lines = ShanhaiLoadingLayout.wrap(text, 24, s -> s.length() * 6);
        assertEquals(text, String.join("", lines));
        assertTrue(lines.stream().allMatch(s -> s.length() <= 4));
        assertEquals(text, String.join("", ShanhaiLoadingLayout.wrap(text, 0, s -> s.length() * 6)));
    }
}
