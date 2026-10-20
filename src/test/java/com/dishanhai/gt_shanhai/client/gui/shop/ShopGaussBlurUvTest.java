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
    void scissorRectIsBottomLeftPixelsWithMargin() {
        // uv 0.10..0.40 × 0.70..0.90，100x200：x 10..40，y(自底) 140..180；四边各放宽 2px
        int[] s = ShopGaussBlur.toScissorRect(new float[]{0.10f, 0.70f, 0.40f, 0.90f}, 100, 200, 2);
        assertArrayEquals(new int[]{8, 138, 34, 44}, s);
    }

    @Test
    void scissorRectClampsToFramebufferAndRejectsEmpty() {
        int[] s = ShopGaussBlur.toScissorRect(new float[]{0f, 0f, 1f, 1f}, 100, 200, 5);
        assertArrayEquals(new int[]{0, 0, 100, 200}, s);
        assertEquals(0, ShopGaussBlur.toScissorRect(new float[]{0.5f, 0.5f, 0.5f, 0.9f}, 100, 100, 0)[2]);
    }

    @Test
    void returnsEmptyForDegenerateInput() {
        assertEquals(0f, ShopGaussBlur.toUvRect(0, 0, 0, 10, 100, 100)[2], 1e-6f);
        assertEquals(0f, ShopGaussBlur.toUvRect(0, 0, 10, 10, 0, 100)[2], 1e-6f);
    }
}
