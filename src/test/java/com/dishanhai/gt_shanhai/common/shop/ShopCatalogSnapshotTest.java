package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShopCatalogSnapshotTest {

    @Test
    void layoutKeepsOrderExcludesHiddenGroupsAndSplitsByBudget() throws Exception {
        Class<?> descriptorType = Class.forName(
                "com.dishanhai.gt_shanhai.common.shop.ShopCatalogSnapshot$Descriptor");
        Constructor<?> constructor = descriptorType.getConstructor(
                String.class, boolean.class, String.class, String.class, List.class, int.class, String.class);
        List<Object> descriptors = List.of(
                constructor.newInstance("无限盘区/前期", false, "a", "", List.of("mod:a"), 30, "stable-a"),
                constructor.newInstance("无限盘区/前期", false, "b", "a", List.of("mod:b"), 30, "stable-b"),
                constructor.newInstance("隐藏", true, "secret", "", List.of("mod:c"), 30, "stable-c"));

        Class<?> snapshotType = Class.forName(
                "com.dishanhai.gt_shanhai.common.shop.ShopCatalogSnapshot");
        Method layoutMethod = snapshotType.getMethod("layout", List.class, int.class, int.class);
        Object layout = layoutMethod.invoke(null, descriptors, 50, 2);

        Method groupKeys = layout.getClass().getMethod("groupKeys", String.class, String.class);
        Method chunks = layout.getClass().getMethod("chunks");
        Method linkKeys = layout.getClass().getMethod("linkKeyToEntryKey");

        assertEquals(List.of(0L, 1L), groupKeys.invoke(layout, "无限盘区", "前期"));
        assertEquals(List.of(), groupKeys.invoke(layout, "隐藏", ""));
        assertEquals(3, ((List<?>) chunks.invoke(layout)).size());
        assertEquals(0L, ((Map<?, ?>) linkKeys.invoke(layout)).get("a"));
    }
}
