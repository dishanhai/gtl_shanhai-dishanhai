package com.dishanhai.gt_shanhai.client.loading;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：正式動畫幾何的動態、固定預算及淡出。 */
class ShanhaiLoadingSceneTest {
    @Test void animatedFramesChangeAndStayWithinAFixedGeometryBudget() {
        var first = ShanhaiLoadingScene.frame(640, 360, 0, 1, false);
        var later = ShanhaiLoadingScene.frame(640, 360, 1000, 1, false);
        assertNotEquals(first, later);
        assertTrue(first.size() < 640);
        assertEquals(first.size(), later.size());
        for (var quad : later) {
            assertTrue(Float.isFinite(quad.x1()) && Float.isFinite(quad.y1()));
            assertTrue(Float.isFinite(quad.x4()) && Float.isFinite(quad.y4()));
        }
    }

    @Test void reduceMotionProducesIdenticalGeometryAtDifferentTimes() {
        assertEquals(ShanhaiLoadingScene.frame(640, 360, 0, 1, true),
                ShanhaiLoadingScene.frame(640, 360, 12_000, 1, true));
    }

    @Test void fadeOutRemovesEveryVertexAlpha() {
        for (var quad : ShanhaiLoadingScene.frame(900, 360, 1000, 0, false)) {
            assertEquals(0, quad.bottomColor() >>> 24);
            assertEquals(0, quad.topColor() >>> 24);
        }
    }
}
