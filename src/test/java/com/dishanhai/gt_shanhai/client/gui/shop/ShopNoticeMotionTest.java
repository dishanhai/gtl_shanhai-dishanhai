package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：提示被推擠或提早關閉時仍從當前狀態接續。 */
class ShopNoticeMotionTest {
    @Test void fadesInAndLeavesFromItsCurrentOpacity() {
        var motion = new ShopNoticeMotion(-12f, 1000L);
        motion.moveTo(0f, 1000L);
        assertEquals(0f, motion.opacity(1000L, false));
        float midway = motion.opacity(1100L, false);
        assertTrue(midway > 0f && midway < 1f);
        motion.leave(1100L);
        assertEquals(midway, motion.opacity(1100L, false), 0.0001f);
        assertTrue(motion.opacity(1200L, false) < midway);
        assertEquals(0f, motion.opacity(1320L, false));
        assertTrue(motion.finished(1320L));
    }

    @Test void retargetingPreservesCurrentPositionAndSettlesAtActualRowHeight() {
        var motion = new ShopNoticeMotion(-12f, 1000L);
        motion.moveTo(0f, 1000L);
        for (long t = 1000; t <= 1080; t += 10) motion.offset(t, false);
        float before = motion.offset(1080L, false);
        motion.moveTo(48f, 1080L);
        assertEquals(before, motion.offset(1080L, false), 0.0001f);
        for (long t = 1090; t <= 1800; t += 10) motion.offset(t, false);
        assertEquals(48f, motion.offset(1800L, false), 0.05f);
    }

    @Test void reduceMotionKeepsContentButSnapsPositionAndSkipsExit() {
        var motion = new ShopNoticeMotion(-12f, 1000L);
        motion.moveTo(32f, 1000L);
        assertEquals(32f, motion.offset(1000L, true));
        assertEquals(1f, motion.opacity(1000L, true));
        motion.leave(1100L);
        assertEquals(0f, motion.opacity(1100L, true));
    }
}
