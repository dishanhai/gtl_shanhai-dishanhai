package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 已选清单的身份、顺序和 long 数量契约（山海署名）。 */
class PickerSelectionTest {
    private static final class Entry implements PickerItem {
        final String id; long count; final boolean quantity;
        Entry(String id, long count) { this(id, count, true); }
        Entry(String id, long count, boolean quantity) { this.id = id; this.count = count; this.quantity = quantity; }
        public long count() { return count; }
        public void count(long value) { count = value; }
        public boolean hasQuantity() { return quantity; }
    }
    @Test void duplicateKeepsOriginalObjectAndBackingList() {
        List<Entry> backing = new ArrayList<>();
        PickerSelection<Entry> s = new PickerSelection<>(backing, (a, b) -> a.id.equals(b.id));
        Entry first = new Entry("a", 9);
        assertSame(first, s.add(first));
        assertSame(first, s.add(new Entry("a", 4)));
        assertEquals(9, first.count);
        assertEquals(List.of(first), backing);
        assertFalse(s.toggle(new Entry("a", 1)));
        assertTrue(backing.isEmpty());
    }
    @Test void insertionGapsMoveWithoutReplacingObjects() {
        Entry a = new Entry("a", 1), b = new Entry("b", 1), c = new Entry("c", 1);
        List<Entry> backing = new ArrayList<>(List.of(a, b, c));
        PickerSelection<Entry> s = new PickerSelection<>(backing, (x, y) -> x.id.equals(y.id));
        assertEquals(2, s.moveToGap(0, 3));
        assertEquals(List.of(b, c, a), backing);
        assertEquals(0, s.moveToGap(2, -20));
        assertEquals(List.of(a, b, c), backing);
        assertEquals(-1, s.moveToGap(-1, 0));
        assertSame(b, s.remove(1));
    }
    @Test void quantitiesSaturateAndTexturesAreSkipped() {
        Entry a = new Entry("a", Long.MAX_VALUE - 2), texture = new Entry("png", 1, false);
        PickerSelection<Entry> s = new PickerSelection<>(new ArrayList<>(List.of(a, texture)), (x, y) -> x.id.equals(y.id));
        assertTrue(s.step(a, 100)); assertEquals(Long.MAX_VALUE, a.count);
        assertFalse(s.step(a, 1));
        s.step(a, Long.MIN_VALUE); assertEquals(1, a.count);
        s.all(98); assertEquals(98, a.count); assertEquals(1, texture.count);
        assertEquals(Long.MAX_VALUE, PickerSelection.parseCount("9223372036854775808"));
        assertEquals(1, PickerSelection.parseCount("0"));
        assertEquals(1, PickerSelection.parseCount(""));
    }
}
