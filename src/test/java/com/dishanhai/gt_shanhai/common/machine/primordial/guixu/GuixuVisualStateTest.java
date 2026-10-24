package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuixuVisualStateTest {
    @Test
    void transitionIsSmoothAndPauseDoesNotAdvanceTheEffect() {
        var visuals = new GuixuVisualState();
        var idle = visuals.advance(GuixuOperationState.IDLE, 0.0);
        var moving = visuals.advance(GuixuOperationState.PROCESSING, 0.1);
        assertTrue(moving.density() > idle.density() && moving.density() < 1.0F);
        assertEquals(moving, visuals.advance(GuixuOperationState.PROCESSING, 0.1));
        var dimmed = visuals.advance(GuixuOperationState.BLOCKED, 4.0);
        assertTrue(dimmed.density() < moving.density());
    }

    @Test
    void frameRateDoesNotChangeTheTransitionOrLeaveGenerationJetsRunning() {
        var slow = new GuixuVisualState();
        var fast = new GuixuVisualState();
        slow.advance(GuixuOperationState.GENERATING, 0.0);
        fast.advance(GuixuOperationState.GENERATING, 0.0);
        var oneFrame = slow.advance(GuixuOperationState.GENERATING, 1.0);
        GuixuVisualState.Profile manyFrames = null;
        for (int i = 1; i <= 60; i++) manyFrames = fast.advance(GuixuOperationState.GENERATING, i / 60.0);
        assertEquals(oneFrame.density(), manyFrames.density(), 1.0e-5F);
        assertEquals(oneFrame.jets(), manyFrames.jets(), 1.0e-5F);
        assertTrue(fast.advance(GuixuOperationState.IDLE, 5.0).jets() < 0.001F);
    }
}
