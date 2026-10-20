package com.dishanhai.gt_shanhai.client.gui.scaled;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringTableTest {

    @Test
    void followCreatesAtZeroAndGlidesTowardTheTarget() {
        SpringTable<String> t = new SpringTable<>(Spring.HOVER);
        assertEquals(0f, t.follow("a", 0f, 0L));
        // 改目标和 update 落在同一毫秒时 dt=0，这一帧不动；下一帧开始走
        assertEquals(0f, t.follow("a", 1f, 16L));
        float v1 = t.follow("a", 1f, 32L);
        assertTrue(v1 > 0f && v1 < 1f, "第二帧应该已经在动但没到位：" + v1);
        float last = v1;
        for (long now = 48L; now < 600L; now += 16L) last = t.follow("a", 1f, now);
        assertEquals(1f, last);
    }

    @Test
    void followIsIdempotentWhenTheTargetDoesNotChange() {
        SpringTable<String> t = new SpringTable<>(Spring.HOVER);
        t.follow("a", 1f, 0L);
        Spring s = t.get("a", 0L);
        t.follow("a", 1f, 16L);
        assertSame(s, t.get("a", 16L));
        assertEquals(1f, s.target());
    }

    @Test
    void startBuildsASpringThatWaitsForItsDelay() {
        SpringTable<Long> t = new SpringTable<>(Spring.STAGGER);
        t.start(7L, 0f, 1f, 1_000L, 100L);
        assertEquals(0f, t.valueOr(7L, 1f, 1_050L));
        assertTrue(t.valueOr(7L, 1f, 1_200L) > 0f);
    }

    @Test
    void valueOrReturnsTheFallbackForUnknownKeys() {
        SpringTable<Long> t = new SpringTable<>(Spring.STAGGER);
        assertEquals(1f, t.valueOr(99L, 1f, 0L));
        assertEquals(0, t.size());
    }

    @Test
    void sweepDropsOnlyIdleEntries() {
        SpringTable<String> t = new SpringTable<>(Spring.HOVER);
        t.follow("old", 0f, 0L);
        t.follow("fresh", 0f, 5_000L);
        t.sweep(5_000L);
        assertEquals(1, t.size());
        assertFalse(t.contains("old"));
        assertTrue(t.contains("fresh"));
    }

    @Test
    void exceedingCapacityClearsTheTableInsteadOfGrowingForever() {
        SpringTable<Integer> t = new SpringTable<>(Spring.HOVER);
        for (int i = 0; i < SpringTable.CAPACITY; i++) t.follow(i, 0f, 0L);
        assertEquals(SpringTable.CAPACITY, t.size());
        t.follow(SpringTable.CAPACITY, 0f, 0L);
        assertEquals(1, t.size());
    }

    @Test
    void clearEmptiesTheTable() {
        SpringTable<String> t = new SpringTable<>(Spring.HOVER);
        t.follow("a", 1f, 0L);
        t.clear();
        assertEquals(0, t.size());
    }
}
