package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ShopGridViewportTest {

    @Test
    void visibleRangeIncludesPartialRowsAndOverscan() throws Exception {
        Class<?> type = Class.forName("com.dishanhai.gt_shanhai.common.shop.ShopGridViewport");
        Method visibleRange = type.getMethod("visibleRange",
                int.class, int.class, int.class, int.class, int.class, int.class);

        Object range = visibleRange.invoke(null, 130, 10, 19, 100, 37, 1);

        assertNotNull(range);
        assertEquals(0, range.getClass().getMethod("fromInclusive").invoke(range));
        assertEquals(50, range.getClass().getMethod("toExclusive").invoke(range));
    }

    @Test
    void hitIndexRejectsGapsAndOutsideCoordinates() throws Exception {
        Class<?> type = Class.forName("com.dishanhai.gt_shanhai.common.shop.ShopGridViewport");
        Method indexAt = type.getMethod("indexAt",
                int.class, int.class, int.class, int.class, int.class, int.class,
                int.class, int.class, int.class, int.class, int.class, double.class, double.class);

        assertEquals(11, indexAt.invoke(null,
                130, 10, 71, 37, 70, 36, 10, 10, 710, 120, 0, 82.0, 48.0));
        assertEquals(-1, indexAt.invoke(null,
                130, 10, 71, 37, 70, 36, 10, 10, 710, 120, 0, 80.0, 46.0));
        assertEquals(-1, indexAt.invoke(null,
                130, 10, 71, 37, 70, 36, 10, 10, 710, 120, 0, 9.0, 48.0));
    }
}
