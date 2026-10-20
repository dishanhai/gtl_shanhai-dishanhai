package com.dishanhai.gt_shanhai.client.gui.scaled;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuiMotionTest {

    @Test
    void lerpColorHitsEndpointsAndMidpointPerChannel() {
        int a = 0xFF102030;
        int b = 0xFF506070;
        assertEquals(a, GuiMotion.lerpColor(a, b, 0f));
        assertEquals(b, GuiMotion.lerpColor(a, b, 1f));
        assertEquals(0xFF304050, GuiMotion.lerpColor(a, b, 0.5f));
    }

    @Test
    void lerpColorInterpolatesAlphaAndClampsT() {
        assertEquals(0x80000000, GuiMotion.lerpColor(0x00000000, 0xFF000000, 0.5f));
        assertEquals(0xFF506070, GuiMotion.lerpColor(0xFF102030, 0xFF506070, 3f));
        assertEquals(0xFF102030, GuiMotion.lerpColor(0xFF102030, 0xFF506070, -1f));
    }

    @Test
    void panelScaleSharesTheEndpointsOfPopScale() {
        assertEquals(0.94f, GuiMotion.panelScale(0f), 1e-6f);
        assertEquals(1f, GuiMotion.panelScale(1f), 1e-6f);
        // 弹簧值可以略超过 1 或略低于 0，缩放仍夹在 [0.94, 1]
        assertEquals(1f, GuiMotion.panelScale(1.02f), 1e-6f);
        assertEquals(0.94f, GuiMotion.panelScale(-0.1f), 1e-6f);
        assertEquals(0.97f, GuiMotion.panelScale(0.5f), 1e-6f);
    }

    @Test
    void clamp01AndLerp() {
        assertEquals(0f, GuiMotion.clamp01(-2f));
        assertEquals(1f, GuiMotion.clamp01(2f));
        assertEquals(0.25f, GuiMotion.clamp01(0.25f));
        assertEquals(15f, GuiMotion.lerp(10f, 20f, 0.5f), 1e-6f);
    }
}
