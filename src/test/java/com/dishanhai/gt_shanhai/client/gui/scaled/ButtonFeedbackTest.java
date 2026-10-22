package com.dishanhai.gt_shanhai.client.gui.scaled;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButtonFeedbackTest {

    private static final long A = 11L;
    private static final long B = 22L;

    private static float press(ButtonFeedback fb, long key, long now) {
        return fb.visual(key, key, true, now, false).press();
    }

    /** 按钮在 (0,0) 20×10；返回本次按下的时刻。 */
    private static long pressA(ButtonFeedback fb, long now) {
        assertTrue(fb.press(A, 0, 0, 20, 10, true, now));
        return now;
    }

    @Test
    void pressSinksQuicklyAndReleaseBouncesPastZero() {
        ButtonFeedback fb = new ButtonFeedback();
        long now = pressA(fb, 1_000L);
        for (int i = 0; i < 3; i++) {
            now += 16L;
            press(fb, A, now);
        }
        assertTrue(press(fb, A, now) > 0.6f, "48ms 内应已明显压下：" + press(fb, A, now));

        assertTrue(fb.release(now));
        float min = 1f;
        for (int i = 0; i < 40; i++) {
            now += 16L;
            min = Math.min(min, press(fb, A, now));
        }
        assertTrue(min < 0f, "松开应越过 0 回弹一点：" + min);
        assertEquals(0f, press(fb, A, now), "600ms 内回到静止");
    }

    @Test
    void releaseReportsOnlyOnceAndCancelWithoutHoldIsHarmless() {
        ButtonFeedback fb = new ButtonFeedback();
        fb.cancel(0L);
        assertFalse(fb.release(0L));
        pressA(fb, 0L);
        assertTrue(fb.holding());
        assertTrue(fb.release(10L));
        assertFalse(fb.release(20L));
        assertFalse(fb.holding());
    }

    @Test
    void leavingTheButtonLiftsItButKeepsTheHold() {
        ButtonFeedback fb = new ButtonFeedback();
        long now = pressA(fb, 0L);
        for (int i = 0; i < 6; i++) {
            now += 16L;
            fb.pointer(5, 5, now);
            press(fb, A, now);
        }
        for (int i = 0; i < 40; i++) {
            now += 16L;
            fb.pointer(50, 50, now);
            press(fb, A, now);
        }
        assertTrue(fb.holding(), "拖出按钮不算松开");
        assertTrue(Math.abs(press(fb, A, now)) < 0.01f, "拖出后按钮应弹回");
        for (int i = 0; i < 6; i++) {
            now += 16L;
            fb.pointer(5, 5, now);
            press(fb, A, now);
        }
        assertTrue(press(fb, A, now) > 0.6f, "拖回来应重新压下");
    }

    @Test
    void repeatFollowsTheCurveWhileInside() {
        ButtonFeedback fb = new ButtonFeedback();
        AtomicInteger count = new AtomicInteger();
        AtomicInteger lastIndex = new AtomicInteger();
        pressA(fb, 0L);
        fb.holdRepeat(RepeatCurve.STEPPER, i -> {
            count.incrementAndGet();
            lastIndex.set(i);
            return true;
        }, 0L);
        long now = 0L;
        while (now < 992L) {
            now += 16L;
            fb.pointer(5, 5, now);
        }
        assertEquals(RepeatCurve.STEPPER.firesBy(now), count.get());
        assertEquals(count.get(), lastIndex.get(), "连发序号从 1 递增");
    }

    @Test
    void repeatPausesOutsideAndDoesNotCatchUpOnReturn() {
        ButtonFeedback fb = new ButtonFeedback();
        AtomicInteger count = new AtomicInteger();
        pressA(fb, 0L);
        fb.holdRepeat(RepeatCurve.STEPPER, i -> {
            count.incrementAndGet();
            return true;
        }, 0L);
        long now = 0L;
        while (now < 496L) {
            now += 16L;
            fb.pointer(5, 5, now);
        }
        assertEquals(1, count.get());
        while (now < 2_496L) {
            now += 16L;
            fb.pointer(50, 50, now);
        }
        assertEquals(1, count.get(), "游标在外面不连发");
        while (now < 2_608L) {
            now += 16L;
            fb.pointer(5, 5, now);
        }
        // 外面待的 2000ms 不计入按住时长：有效时长 608ms → 第 2 次在 520ms，第 3 次在 622ms
        assertEquals(2, count.get(), "回来后接着原节奏，不补发");
    }

    @Test
    void aStalledFrameIsCappedAtTheBurstLimit() {
        ButtonFeedback fb = new ButtonFeedback();
        AtomicInteger count = new AtomicInteger();
        pressA(fb, 0L);
        fb.holdRepeat(RepeatCurve.STEPPER, i -> {
            count.incrementAndGet();
            return true;
        }, 0L);
        fb.pointer(5, 5, 16L);
        assertEquals(ButtonFeedback.MAX_REPEAT_BURST, fb.pointer(5, 5, 3_000L));
        assertEquals(ButtonFeedback.MAX_REPEAT_BURST, count.get());
        fb.pointer(5, 5, 3_016L);
        assertTrue(count.get() <= ButtonFeedback.MAX_REPEAT_BURST + 1, "卡顿积压的次数要丢掉：" + count.get());
    }

    @Test
    void returningFalseStopsTheRepeat() {
        ButtonFeedback fb = new ButtonFeedback();
        AtomicInteger count = new AtomicInteger();
        pressA(fb, 0L);
        fb.holdRepeat(RepeatCurve.STEPPER, i -> count.incrementAndGet() < 2, 0L);
        long now = 0L;
        while (now < 3_000L) {
            now += 16L;
            fb.pointer(5, 5, now);
        }
        assertEquals(2, count.get());
        assertTrue(fb.holding(), "连发停了，按钮仍按着");
    }

    @Test
    void releaseAndCancelStopRepeating() {
        ButtonFeedback fb = new ButtonFeedback();
        AtomicInteger count = new AtomicInteger();
        pressA(fb, 0L);
        fb.holdRepeat(RepeatCurve.STEPPER, i -> count.incrementAndGet() > 0, 0L);
        fb.release(100L);
        pressA(fb, 200L);
        fb.holdRepeat(RepeatCurve.STEPPER, i -> count.incrementAndGet() > 0, 200L);
        fb.cancel(300L);
        long now = 300L;
        while (now < 2_000L) {
            now += 16L;
            fb.pointer(5, 5, now);
        }
        assertEquals(0, count.get());
        assertEquals(0f, press(fb, A, now));
    }

    @Test
    void tryBeginDebouncesAndBlocksWhilePending() {
        ButtonFeedback fb = new ButtonFeedback();
        assertTrue(fb.tryBegin(A, 0L));
        assertFalse(fb.tryBegin(A, 100L), "300ms 内第二下吞掉");
        assertTrue(fb.tryBegin(B, 100L), "别的按钮不受影响");
        assertTrue(fb.tryBegin(A, 400L));
        fb.pending(A, 7, 400L);
        assertEquals(ButtonFeedback.Phase.PENDING, fb.phase(A, 500L));
        assertFalse(fb.tryBegin(A, 1_000L), "等待回执时不能再下单");
        assertTrue(fb.resolve(7, ButtonFeedback.Tone.SUCCESS, 1_200L));
        assertEquals(ButtonFeedback.Phase.FLASH, fb.phase(A, 1_200L));
        assertEquals(ButtonFeedback.Tone.SUCCESS, fb.flashTone(A, 1_200L));
        assertTrue(fb.tryBegin(A, 1_600L), "回执到了就能再下单");
    }

    @Test
    void pendingTimesOutToNeutralAndLateResultsAreIgnored() {
        ButtonFeedback fb = new ButtonFeedback();
        fb.tryBegin(A, 0L);
        fb.pending(A, 9, 0L);
        assertEquals(ButtonFeedback.Phase.PENDING, fb.phase(A, ButtonFeedback.PENDING_TIMEOUT_MS - 1L));
        assertEquals(ButtonFeedback.Phase.FLASH, fb.phase(A, ButtonFeedback.PENDING_TIMEOUT_MS));
        assertEquals(ButtonFeedback.Tone.NEUTRAL, fb.flashTone(A, ButtonFeedback.PENDING_TIMEOUT_MS));
        assertFalse(fb.resolve(9, ButtonFeedback.Tone.SUCCESS, ButtonFeedback.PENDING_TIMEOUT_MS + 1L));
    }

    @Test
    void unknownRequestIdsAreIgnored() {
        ButtonFeedback fb = new ButtonFeedback();
        assertFalse(fb.resolve(42, ButtonFeedback.Tone.SUCCESS, 0L));
        fb.tryBegin(A, 0L);
        fb.pending(A, 1, 0L);
        assertFalse(fb.resolve(2, ButtonFeedback.Tone.FAILED, 10L));
        assertEquals(ButtonFeedback.Phase.PENDING, fb.phase(A, 10L));
    }

    @Test
    void flashExpiresBackToIdleAndKeysAreIndependent() {
        ButtonFeedback fb = new ButtonFeedback();
        fb.flash(A, ButtonFeedback.Tone.FAILED, 0L);
        assertEquals(ButtonFeedback.Phase.FLASH, fb.phase(A, 100L));
        assertEquals(ButtonFeedback.Phase.IDLE, fb.phase(B, 100L));
        assertEquals(ButtonFeedback.Phase.IDLE, fb.phase(A, ButtonFeedback.FLASH_MS));
        fb.tick(ButtonFeedback.FLASH_MS + 5_000L);
        assertEquals(ButtonFeedback.Phase.IDLE, fb.phase(A, ButtonFeedback.FLASH_MS + 5_000L));
    }

    @Test
    void failedFlashShakesAndSuccessDoesNot() {
        ButtonFeedback fb = new ButtonFeedback();
        fb.flash(A, ButtonFeedback.Tone.FAILED, 0L);
        fb.flash(B, ButtonFeedback.Tone.SUCCESS, 0L);
        boolean shookA = false;
        for (long t = 0L; t < ButtonFeedback.SHAKE_MS; t += 8L) {
            if (fb.visual(A, A, true, t, false).shakeX() != 0f) shookA = true;
            assertEquals(0f, fb.visual(B, B, true, t, false).shakeX());
        }
        assertTrue(shookA, "失败要左右抖");
        assertTrue(fb.visual(B, B, true, 120L, false).flashMix() > 0.5f, "成功也要闪色");
        assertEquals(0f, fb.visual(A, A, true, ButtonFeedback.SHAKE_MS, false).shakeX());
    }

    @Test
    void shakeOffsetStartsAndEndsAtRestAndOscillates() {
        assertEquals(0f, ButtonFeedback.shakeOffset(0L, 3f, 320L));
        assertEquals(0f, ButtonFeedback.shakeOffset(-5L, 3f, 320L));
        assertEquals(0f, ButtonFeedback.shakeOffset(320L, 3f, 320L));
        int signChanges = 0;
        float prev = 0f;
        for (long t = 1L; t < 320L; t++) {
            float v = ButtonFeedback.shakeOffset(t, 3f, 320L);
            assertTrue(Math.abs(v) <= 3f, "振幅越界：" + v);
            if (prev != 0f && v != 0f && Math.signum(v) != Math.signum(prev)) signChanges++;
            if (v != 0f) prev = v;
        }
        assertTrue(signChanges >= 2, "至少来回两下：" + signChanges);
    }

    @Test
    void flashEnvelopeRisesHoldsAndFades() {
        assertEquals(0f, ButtonFeedback.flashEnvelope(0L));
        float rising = ButtonFeedback.flashEnvelope(30L);
        assertTrue(rising > 0f && rising < 1f);
        assertEquals(1f, ButtonFeedback.flashEnvelope(100L));
        float prev = 1f;
        for (long t = 260L; t <= ButtonFeedback.FLASH_MS; t += 20L) {
            float v = ButtonFeedback.flashEnvelope(t);
            assertTrue(v <= prev, "淡出不能回升");
            prev = v;
        }
        assertEquals(0f, ButtonFeedback.flashEnvelope(ButtonFeedback.FLASH_MS));
        assertEquals(0f, ButtonFeedback.flashEnvelope(-1L));
    }

    @Test
    void reduceMotionKeepsColourButDropsMovement() {
        ButtonFeedback fb = new ButtonFeedback();
        long now = pressA(fb, 0L);
        fb.flash(A, ButtonFeedback.Tone.FAILED, 0L);
        for (int i = 0; i < 6; i++) {
            now += 16L;
            ButtonFeedback.Visual v = fb.visual(A, A, true, now, true);
            assertEquals(1f, v.scale());
            assertEquals(0f, v.shakeX());
        }
        assertTrue(fb.visual(A, A, true, now, true).flashMix() > 0.5f, "颜色闪仍保留");
    }

    @Test
    void pendingCarriesALoopingSweepPhase() {
        ButtonFeedback fb = new ButtonFeedback();
        fb.tryBegin(A, 0L);
        fb.pending(A, 3, 0L);
        ButtonFeedback.Visual v = fb.visual(A, A, true, ButtonFeedback.PENDING_PERIOD_MS / 2L, false);
        assertTrue(v.pending());
        assertEquals(0.5f, v.pendingPhase(), 0.01f);
        assertFalse(fb.visual(B, B, true, 10L, false).pending());
    }

    @Test
    void slowCraftingCanWaitLongerWithoutChangingTradeTimeouts() {
        ButtonFeedback fb = new ButtonFeedback();
        fb.pending(A, 10, 0L, 60_000L);
        fb.pending(B, 11, 0L);
        assertEquals(ButtonFeedback.Phase.PENDING, fb.phase(A, 6_000L));
        assertEquals(ButtonFeedback.Phase.IDLE, fb.phase(B, 6_000L));
        assertTrue(fb.resolve(10, ButtonFeedback.Tone.SUCCESS, 7_000L));
        fb.pending(A, 12, 8_000L, 60_000L);
        assertEquals(ButtonFeedback.Phase.FLASH, fb.phase(A, 68_000L));
        assertFalse(fb.resolve(12, ButtonFeedback.Tone.SUCCESS, 68_000L));
    }

    @Test
    void serverOwnedCalculationTimeoutAllowsEveryValidMultiTargetPlan() {
        ButtonFeedback fb = new ButtonFeedback();
        fb.pending(A, 13, 0L, Long.MAX_VALUE);
        // 31 個目標、每項 397 tick：都低於服務端單項 400 tick 上限，但整輪超過十分鐘。
        long calculatedAt = 31L * 397L * 50L;
        fb.tick(calculatedAt);
        assertTrue(fb.visual(A, A, true, calculatedAt, false).pending());
        assertTrue(fb.resolve(13, ButtonFeedback.Tone.SUCCESS, calculatedAt));
    }

    @Test
    void disabledPressOnlyNudges() {
        ButtonFeedback fb = new ButtonFeedback();
        assertFalse(fb.press(A, 0, 0, 20, 10, false, 0L));
        assertFalse(fb.holding());
        boolean nudged = false;
        for (long t = 8L; t < ButtonFeedback.NUDGE_MS; t += 8L) {
            ButtonFeedback.Visual v = fb.visual(A, A, true, t, false);
            assertEquals(0f, v.press(), "不可用按钮不压下");
            if (v.shakeX() != 0f) nudged = true;
        }
        assertTrue(nudged, "不可用按钮轻抖一下");
    }

    @Test
    void tintAndShadeAreIdentityAtRest() {
        ButtonFeedback fb = new ButtonFeedback();
        ButtonFeedback.Visual rest = fb.visual(A, A, true, 0L, false);
        assertEquals(0f, rest.flashMix());
        assertEquals(1f, rest.scale());
        assertEquals(0xFF123456, ButtonFeedback.tint(0xFF123456, rest));
        assertEquals(0xFF123456, ButtonFeedback.shade(0xFF123456, rest));
        fb.flash(B, ButtonFeedback.Tone.SUCCESS, 0L);
        assertNotEquals(0xFF123456, ButtonFeedback.tint(0xFF123456, fb.visual(B, B, true, 150L, false)));
    }

    @Test
    void pressScaleIsClamped() {
        assertEquals(1f, ButtonFeedback.pressScale(0f), 1e-6f);
        assertEquals(0.95f, ButtonFeedback.pressScale(1f), 1e-6f);
        assertEquals(0.94f, ButtonFeedback.pressScale(5f), 1e-6f);
        assertEquals(1.03f, ButtonFeedback.pressScale(-5f), 1e-6f);
    }

    @Test
    void hoverSharesTheCallersSpringTable() {
        SpringTable<Long> table = new SpringTable<>(Spring.HOVER);
        ButtonFeedback fb = new ButtonFeedback(table);
        long now = 0L;
        float v = 0f;
        for (int i = 0; i < 40; i++) {
            now += 16L;
            v = fb.hover(5L, true, now);
        }
        assertEquals(1f, v);
        assertTrue(table.contains(5L));
    }
}
