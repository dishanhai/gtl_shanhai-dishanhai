package com.dishanhai.gt_shanhai.client.gui.glass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GlassGeometryTest {

    @Test
    void scalesGuiRectToFramebufferPixels() {
        // 400x300 的 GUI 放到 1600x1200 的 framebuffer，放大 4 倍
        PixelRect r = GlassGeometry.toPixels(new GuiRect(10f, 20f, 30f, 40f), 1600, 1200, 400, 300);
        assertEquals(new PixelRect(40, 80, 120, 160), r);
    }

    @Test
    void roundsByCornersSoTheRightEdgeDoesNotDrift() {
        // 放大 3 倍：左上 1.5 → 2，右下 3.0 → 3，宽 1。若左上和宽度各自取整，右边会落在 2+round(1.5)=4。
        PixelRect r = GlassGeometry.toPixels(new GuiRect(0.5f, 0.5f, 0.5f, 0.5f), 300, 300, 100, 100);
        assertEquals(new PixelRect(2, 2, 1, 1), r);
    }

    @Test
    void emptyOrInvalidInputGivesEmptyRect() {
        assertTrue(GlassGeometry.toPixels(new GuiRect(0f, 0f, 0f, 10f), 100, 100, 100, 100).isEmpty());
        assertTrue(GlassGeometry.toPixels(new GuiRect(0f, 0f, 10f, 10f), 100, 100, 0, 100).isEmpty());
        assertTrue(GlassGeometry.toPixels(new GuiRect(0f, 0f, 10f, 10f), 0, 100, 100, 100).isEmpty());
    }

    @Test
    void affineAppliesScaleThenTranslate() {
        GuiRect r = GlassGeometry.applyAffine(new GuiRect(10f, 10f, 100f, 50f), 2f, 2f, 5f, 7f);
        assertEquals(new GuiRect(25f, 27f, 200f, 100f), r);
    }

    @Test
    void unionIsTheBoundingBoxOfBothRects() {
        // 锚点按钮在弹层正上方、右对齐：外接矩形把两者都装下
        GuiRect anchor = new GuiRect(198f, 52f, 22f, 22f);
        GuiRect popup = new GuiRect(0f, 76f, 220f, 96f);
        assertEquals(new GuiRect(0f, 52f, 220f, 120f), anchor.union(popup));
        assertEquals(anchor.union(popup), popup.union(anchor));
        assertEquals(popup, popup.union(new GuiRect(10f, 80f, 5f, 5f)));
    }

    @Test
    void expandGrowsEveryEdge() {
        assertEquals(new GuiRect(6f, 6f, 28f, 28f),
                GlassGeometry.expand(new GuiRect(10f, 10f, 20f, 20f), 4f));
    }
}
