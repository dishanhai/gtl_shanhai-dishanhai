package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopGaussBlurUvTest {

    @Test
    void mapsTopLeftPixelRectToBottomLeftShaderUv() {
        // 100x200 framebuffer，rect 左上 (10,20)、30x40：
        // u = 0.10..0.40，v 从底部算 = 1 - 60/200 .. 1 - 20/200 = 0.70..0.90
        float[] uv = ShopGaussBlur.toUvRect(10, 20, 30, 40, 100, 200);
        assertArrayEquals(new float[]{0.10f, 0.70f, 0.40f, 0.90f}, uv, 1e-6f);
    }

    @Test
    void clampsRectToTheFramebuffer() {
        float[] uv = ShopGaussBlur.toUvRect(-50, -50, 100, 100, 100, 100);
        assertArrayEquals(new float[]{0f, 0.5f, 0.5f, 1f}, uv, 1e-6f);
    }

    @Test
    void returnsEmptyForDegenerateInput() {
        assertEquals(0f, ShopGaussBlur.toUvRect(0, 0, 0, 10, 100, 100)[2], 1e-6f);
        assertEquals(0f, ShopGaussBlur.toUvRect(0, 0, 10, 10, 0, 100)[2], 1e-6f);
    }
}
