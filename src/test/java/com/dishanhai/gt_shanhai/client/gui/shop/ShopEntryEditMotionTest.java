package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.Spring;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopEntryEditLayout.Row;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 商品编辑器动效状态（纯 Java，山海署名）。 */
class ShopEntryEditMotionTest {

    private static final long T0 = 1_000_000L;

    @Test
    void tabSwitchSlidesInFromTheSideOfTheNewTab() {
        ShopEntryEditMotion m = new ShopEntryEditMotion();
        assertEquals(0f, m.slideX(T0), "没切过页不平移");
        m.tabChanged(0, 2, T0);
        assertEquals(GuiRenderUtil.TAB_SLIDE_DIST, m.slideX(T0), 1e-3f, "往右边的页切：内容从右边滑进来");
        assertEquals(0f, m.slideX(T0 + GuiRenderUtil.MOTION_MS), 1e-3f);
        m.tabChanged(3, 1, T0);
        assertTrue(m.slideX(T0 + 20) < 0f, "往左边的页切：从左边滑进来");
        m.setReduceMotion(true);
        assertEquals(0f, m.slideX(T0 + 20), "减少动效时不平移");
    }

    /** 弹簧单步最多积分 50ms，按 16ms 一帧推进到 {@code until}，返回最后一帧的值。 */
    private static float frames(long from, long until, java.util.function.LongFunction<Float> frame) {
        float v = 0f;
        for (long t = from; t <= until; t += 16) v = frame.apply(t);
        return v;
    }

    @Test
    void underlineSnapsFirstThenGlidesToTheNewTab() {
        ShopEntryEditMotion m = new ShopEntryEditMotion();
        assertArrayEquals(new float[]{10f, 40f}, m.underline(10f, 40f, T0), 1e-3f, "第一次直接到位，不从 0 滑过来");
        m.underline(100f, 60f, T0 + 16);
        float[] mid = m.underline(100f, 60f, T0 + 48);
        assertTrue(mid[0] > 10f && mid[0] < 100f, "换页签时下划线滑过去");
        frames(T0 + 64, T0 + 2_000, t -> m.underline(100f, 60f, t)[0]);
        assertArrayEquals(new float[]{100f, 60f}, m.underline(100f, 60f, T0 + 2_016), 1e-3f);
        m.resetUnderline();
        assertArrayEquals(new float[]{5f, 30f}, m.underline(5f, 30f, T0 + 2_032), 1e-3f, "改窗口大小后重新对齐");
    }

    @Test
    void pageScrollFollowsItsTargetSmoothlyUnlessSnappedOrReduced() {
        ShopEntryEditMotion m = new ShopEntryEditMotion();
        assertEquals(0, m.scroll(0, T0));
        m.scroll(120, T0 + 16);
        int mid = m.scroll(120, T0 + 48);
        assertTrue(mid > 0 && mid < 120, "滚轮一格不是瞬移");
        assertEquals(120f, frames(T0 + 64, T0 + 2_000, t -> (float) m.scroll(120, t)), 1e-3f);
        m.snapScroll(30);
        assertEquals(30, m.scroll(30, T0 + 2_001), "拖滚动条、切页时直接到位");
        m.setReduceMotion(true);
        assertEquals(200, m.scroll(200, T0 + 2_002));
    }

    @Test
    void overlaysOpenAndCloseOnTheSameSpring() {
        ShopEntryEditMotion m = new ShopEntryEditMotion();
        Spring s = new Spring(Spring.PANEL);
        assertFalse(ShopEntryEditMotion.visible(m.panel(s, false, T0)), "没开过不画");
        m.panel(s, true, T0 + 16);
        float opening = m.panel(s, true, T0 + 48);
        assertTrue(opening > 0f && opening < 1f, "弹出来，不是一下子出现");
        assertEquals(1f, frames(T0 + 64, T0 + 2_000, t -> m.panel(s, true, t)), 1e-3f);
        m.panel(s, false, T0 + 2_016);
        float closing = m.panel(s, false, T0 + 2_048);
        assertTrue(ShopEntryEditMotion.visible(closing) && closing < 1f, "关的时候还要画着淡出");
        assertFalse(ShopEntryEditMotion.visible(frames(T0 + 2_064, T0 + 4_000, t -> m.panel(s, false, t))));
        m.setReduceMotion(true);
        assertEquals(1f, m.panel(s, true, T0 + 4_001), "减少动效时直接到位");
    }

    @Test
    void newSlotsPopInAndOnlyThoseSlots() {
        ShopEntryEditMotion m = new ShopEntryEditMotion();
        m.popSlots(2, 1, 2, T0);
        assertEquals(1f, m.slotScale(2, 0, T0), "旧格子不动");
        assertTrue(m.slotScale(2, 1, T0) < 0.7f, "新格子从小弹出来");
        assertTrue(m.slotScale(2, 2, T0) < 0.7f);
        assertEquals(1f, m.slotScale(2, 3, T0));
        assertEquals(1f, m.slotScale(1, 1, T0), "别的排不受影响");
        float peak = 0f;
        for (long t = 0; t <= ShopEntryEditMotion.SLOT_POP_MS; t += 4) peak = Math.max(peak, m.slotScale(2, 1, T0 + t));
        assertTrue(peak > 1f, "落位前略微胀出一点再回弹");
        assertEquals(1f, m.slotScale(2, 1, T0 + ShopEntryEditMotion.SLOT_POP_MS), 1e-3f);
        m.popSlots(2, 0, 1, T0);
        m.setReduceMotion(true);
        assertEquals(1f, m.slotScale(2, 0, T0));
    }

    @Test
    void slotsAfterARemovedOneSlideLeftIntoItsPlace() {
        ShopEntryEditMotion m = new ShopEntryEditMotion();
        m.slotRemoved(1, 2, T0);
        assertEquals(0f, m.slotShiftX(1, 1, T0, 24), "被删格子前面的不动");
        assertEquals(24f, m.slotShiftX(1, 2, T0, 24), 1e-3f, "后面的从原位置往左补上来");
        assertEquals(24f, m.slotShiftX(1, 5, T0, 24), 1e-3f);
        assertEquals(0f, m.slotShiftX(3, 2, T0, 24), "别的排不受影响");
        assertEquals(0f, m.slotShiftX(1, 2, T0 + ShopEntryEditMotion.SLOT_SHIFT_MS, 24), 1e-3f);
        m.slotRemoved(1, 0, T0);
        m.setReduceMotion(true);
        assertEquals(0f, m.slotShiftX(1, 0, T0, 24));
    }

    @Test
    void theRowThatFailedValidationPulsesRedThenFades() {
        ShopEntryEditMotion m = new ShopEntryEditMotion();
        m.startFlash(Row.COIN, T0);
        assertEquals(1f, m.flash(Row.COIN, T0), 1e-3f);
        assertEquals(0f, m.flash(Row.ITEM, T0), "只闪出错那一行");
        boolean dipped = false;
        float prev = 1f;
        for (long t = 0; t < ShopEntryEditMotion.FLASH_MS; t += 10) {
            float v = m.flash(Row.COIN, T0 + t);
            if (v > prev + 1e-4f) dipped = true;
            prev = v;
        }
        assertTrue(dipped, "一明一暗地闪几下");
        assertEquals(0f, m.flash(Row.COIN, T0 + ShopEntryEditMotion.FLASH_MS));
        m.startFlash(Row.COIN, T0);
        m.setReduceMotion(true);
        float a = m.flash(Row.COIN, T0 + 100), b = m.flash(Row.COIN, T0 + 400);
        assertTrue(a > 0f && b < a, "减少动效时不闪，只平平地淡掉");
    }

    @Test
    void rowsThatJustAppearedFadeIn() {
        ShopEntryEditMotion m = new ShopEntryEditMotion();
        m.startReveal(EnumSet.of(Row.SPARK, Row.COIN), T0);
        assertEquals(0f, m.reveal(Row.SPARK, T0), 1e-3f);
        assertEquals(1f, m.reveal(Row.FREE, T0), "一直都在的行不动");
        float mid = m.reveal(Row.COIN, T0 + ShopEntryEditMotion.REVEAL_MS / 2);
        assertTrue(mid > 0f && mid < 1f);
        assertEquals(1f, m.reveal(Row.SPARK, T0 + ShopEntryEditMotion.REVEAL_MS), 1e-3f);
        m.startReveal(EnumSet.of(Row.ITEM), T0);
        m.setReduceMotion(true);
        assertEquals(1f, m.reveal(Row.ITEM, T0));
    }

    @Test
    void emptySlotHintBreathesUnlessMotionIsReduced() {
        ShopEntryEditMotion m = new ShopEntryEditMotion();
        float lo = 1f, hi = 0f;
        for (long t = 0; t < 2_000; t += 20) {
            float v = m.breath(T0 + t);
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        assertTrue(lo >= 0f && hi <= 1f && hi - lo > 0.4f, "空占位格的「?」一呼一吸");
        m.setReduceMotion(true);
        assertEquals(1f, m.breath(T0 + 123));
    }

    @Test
    void openingScalesUpOnceAndRespectsReducedMotion() {
        ShopEntryEditMotion m = new ShopEntryEditMotion();
        float s = m.openScale(System.currentTimeMillis());
        assertTrue(s >= 0.94f && s <= 1f);
        m.setReduceMotion(true);
        assertEquals(1f, m.openScale(System.currentTimeMillis()));
    }
}
