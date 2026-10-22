package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 排序不改来源，平手稳定，模组分区独立于网格排序（山海署名）。 */
class PickerSortTest {
    private final List<PickerSortKey> keys = List.of(
            new PickerSortKey("z:b", "Beta", "z", "Zulu", 2),
            new PickerSortKey("minecraft:a", "Alpha", "minecraft", "Minecraft", 9),
            new PickerSortKey("a:c", "Alpha", "a", "Alpha Mod", 9));
    @Test void defaultNameIdAndCountAreStable() {
        assertArrayEquals(new int[]{0, 1, 2}, PickerSort.order(keys, PickerSortMode.DEFAULT, true, "en_us"));
        assertArrayEquals(new int[]{1, 2, 0}, PickerSort.order(keys, PickerSortMode.NAME, true, "en_us"));
        assertArrayEquals(new int[]{2, 1, 0}, PickerSort.order(keys, PickerSortMode.ID, true, "en_us"));
        assertArrayEquals(new int[]{1, 2, 0}, PickerSort.order(keys, PickerSortMode.COUNT, true, "en_us"));
        assertArrayEquals(new int[]{0, 1, 2}, PickerSort.order(keys, PickerSortMode.COUNT, false, "zh_tw"));
    }
    @Test void minecraftSectionFirstAndInternalOrderPreserved() {
        var sections = PickerSort.sections(keys, new int[]{2, 0, 1}, "en_us");
        assertEquals(List.of("minecraft", "a", "z"), sections.stream().map(s -> s.namespace).toList());
        assertArrayEquals(new int[]{1}, sections.get(0).indices);
        assertArrayEquals(new int[]{2}, sections.get(1).indices);
        assertEquals(PickerSortMode.DEFAULT, PickerSortMode.fromId("bad"));
    }
}
