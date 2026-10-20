package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopGlassStyleTest {

    @Test
    void noneStyleWritesAllZeros() {
        float[] tint = {9f, 9f, 9f, 9f};
        float[] params = {9f, 9f, 9f, 9f};
        float[] edge = {9f, 9f, 9f, 9f};
        ShopGlassStyle.NONE.write(3f, tint, params, edge);
        assertArrayEquals(new float[]{0f, 0f, 0f, 0f}, tint, 0f);
        assertArrayEquals(new float[]{0f, 0f, 0f, 0f}, params, 0f);
        assertArrayEquals(new float[]{0f, 0f, 0f, 0f}, edge, 0f);
    }

    @Test
    void virtualUnitsScaleToPixelsButRatiosDoNot() {
        float[] tint = new float[4];
        float[] params = new float[4];
        float[] edge1 = new float[4];
        float[] edge3 = new float[4];
        ShopGlassStyle.OVERLAY.write(1f, tint, params, edge1);
        ShopGlassStyle.OVERLAY.write(3f, tint, params, edge3);
        assertEquals(edge1[0] * 3f, edge3[0], 1e-5f); // 折射 px
        assertEquals(edge1[1] * 3f, edge3[1], 1e-5f); // 亮边宽 px
        assertEquals(edge1[2] * 3f, edge3[2], 1e-5f); // 阴影半径 px
        assertEquals(edge1[3], edge3[3], 1e-6f);      // 阴影强度是比例，不随缩放
    }

    @Test
    void presetsStayInSaneRanges() {
        ShopGlassStyle[] all = {ShopGlassStyle.OVERLAY, ShopGlassStyle.FLIGHT,
                ShopGlassStyle.SWAP, ShopGlassStyle.LOADING, ShopGlassStyle.NONE};
        float[] tint = new float[4];
        float[] params = new float[4];
        float[] edge = new float[4];
        for (ShopGlassStyle s : all) {
            s.write(1f, tint, params, edge);
            assertTrue(tint[3] >= 0f && tint[3] <= 0.3f, "tint amount");
            assertTrue(params[0] >= 0f && params[0] <= 0.5f, "saturation");
            assertTrue(params[1] >= 0f && params[1] <= 0.08f, "grain");
            assertTrue(params[2] >= 0f && params[2] <= 0.08f, "lift");
            assertTrue(params[3] >= 0f && params[3] <= 1f, "rim");
            assertTrue(edge[3] >= 0f && edge[3] <= 1f, "shadow strength");
        }
    }

    @Test
    void onlyOverlayAndFlightHaveRimAndRefraction() {
        float[] tint = new float[4];
        float[] params = new float[4];
        float[] edge = new float[4];
        for (ShopGlassStyle s : new ShopGlassStyle[]{ShopGlassStyle.SWAP, ShopGlassStyle.LOADING}) {
            s.write(1f, tint, params, edge);
            assertEquals(0f, params[3], 0f);
            assertEquals(0f, edge[0], 0f);
        }
        ShopGlassStyle.OVERLAY.write(1f, tint, params, edge);
        assertTrue(params[3] > 0f && edge[0] > 0f);
        ShopGlassStyle.FLIGHT.write(1f, tint, params, edge);
        assertTrue(params[3] > 0f && edge[0] > 0f);
    }
}
