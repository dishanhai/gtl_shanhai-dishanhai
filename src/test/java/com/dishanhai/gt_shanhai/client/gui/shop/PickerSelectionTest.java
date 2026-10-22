package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

/** 已选清单的身份、顺序和 long 数量契约（山海署名）。 */
class PickerSelectionTest {
    private static final BiPredicate<Entry, Entry> SAME =
            (first, second) -> first.id.equals(second.id) && first.tag.equals(second.tag);

    @Test
    void itemsExposeTheSameMutableList() {
        PickerSelection<Entry> selection = new PickerSelection<>();
        List<Entry> items = selection.getItems();
        Entry original = new Entry("a", 9);

        assertTrue(items.isEmpty());
        items.add(original);

        assertSame(items, selection.getItems());
        assertSame(original, selection.getItems().get(0));
        items.clear();
        assertTrue(selection.getItems().isEmpty());
    }

    @Test
    void duplicateReturnsOriginalWithoutChangingItsCountOrPosition() {
        PickerSelection<Entry> selection = new PickerSelection<>();
        Entry first = new Entry("a", 9);
        Entry second = new Entry("b", 7);

        assertSame(first, selection.add(first, SAME));
        assertSame(second, selection.add(second, SAME));
        assertSame(first, selection.add(new Entry("a", 4), SAME));

        assertEquals(List.of(first, second), selection.getItems());
        assertEquals(9L, first.getCount());
    }

    @Test
    void callerPredicateKeepsDifferentNbtIdentitiesSeparate() {
        PickerSelection<Entry> selection = new PickerSelection<>();
        Entry first = new Entry("a", "first-nbt", 2);
        Entry second = new Entry("a", "second-nbt", 3);

        assertSame(first, selection.add(first, SAME));
        assertSame(second, selection.add(second, SAME));
        assertSame(second, selection.add(new Entry("a", "second-nbt", 100), SAME));

        assertEquals(List.of(first, second), selection.getItems());
        assertEquals(3L, second.getCount());
    }

    @Test
    void removeReportsWhetherAnItemWasRemovedAndKeepsRemainingObjects() {
        PickerSelection<Entry> selection = new PickerSelection<>();
        Entry first = new Entry("a", 2);
        Entry second = new Entry("b", 3);
        selection.add(first, SAME);
        selection.add(second, SAME);

        assertFalse(selection.remove(new Entry("missing", 1)));
        assertTrue(selection.remove(first));
        assertFalse(selection.remove(first));
        assertEquals(1, selection.getItems().size());
        assertSame(second, selection.getItems().get(0));
    }

    @Test
    void toggleReturnsWhetherMatchingIdentityIsSelected() {
        PickerSelection<Entry> selection = new PickerSelection<>();
        Entry first = new Entry("a", "first-nbt", 9);
        Entry second = new Entry("a", "second-nbt", 7);

        assertTrue(selection.toggle(first, SAME));
        assertTrue(selection.toggle(second, SAME));
        assertFalse(selection.toggle(new Entry("a", "first-nbt", 1), SAME));
        assertEquals(List.of(second), selection.getItems());
        assertTrue(selection.toggle(first, SAME));
        assertEquals(List.of(second, first), selection.getItems());
    }

    @Test
    void moveUsesFinalIndexInBothDirectionsWithoutReplacingObjects() {
        PickerSelection<Entry> selection = new PickerSelection<>();
        Entry first = new Entry("a", 1);
        Entry second = new Entry("b", 2);
        Entry third = new Entry("c", 3);
        selection.getItems().addAll(List.of(first, second, third));

        assertTrue(selection.move(0, 1));
        assertEquals(List.of(second, first, third), selection.getItems());
        assertTrue(selection.move(1, 2));
        assertEquals(List.of(second, third, first), selection.getItems());
        assertTrue(selection.move(2, 0));
        assertEquals(List.of(first, second, third), selection.getItems());
        assertSame(first, selection.getItems().get(0));
        assertSame(second, selection.getItems().get(1));
        assertSame(third, selection.getItems().get(2));
    }

    @Test
    void moveClampsDestinationAndRejectsInvalidOrUnchangedSource() {
        PickerSelection<Entry> selection = new PickerSelection<>();
        Entry first = new Entry("a", 1);
        Entry second = new Entry("b", 2);
        Entry third = new Entry("c", 3);
        selection.getItems().addAll(List.of(first, second, third));

        assertFalse(selection.move(-1, 0));
        assertFalse(selection.move(3, 0));
        assertFalse(selection.move(1, 1));
        assertEquals(List.of(first, second, third), selection.getItems());
        assertTrue(selection.move(0, Integer.MAX_VALUE));
        assertEquals(List.of(second, third, first), selection.getItems());
        assertFalse(selection.move(2, Integer.MAX_VALUE));
        assertTrue(selection.move(2, Integer.MIN_VALUE));
        assertEquals(List.of(first, second, third), selection.getItems());
        assertFalse(selection.move(0, Integer.MIN_VALUE));
    }

    @Test
    void emptyAndSingleItemMovesAreNoOps() {
        PickerSelection<Entry> selection = new PickerSelection<>();
        assertFalse(selection.move(0, 0));
        assertFalse(selection.move(0, Integer.MAX_VALUE));

        Entry only = new Entry("a", 1);
        selection.add(only, SAME);
        assertFalse(selection.move(0, Integer.MIN_VALUE));
        assertFalse(selection.move(0, Integer.MAX_VALUE));
        assertSame(only, selection.getItems().get(0));
    }

    @Test
    void stepReturnsNewLongCountAndSaturatesAtBothBounds() {
        PickerSelection<Entry> selection = new PickerSelection<>();
        Entry original = new Entry("a", (long) Integer.MAX_VALUE + 100);
        selection.add(original, SAME);

        assertEquals((long) Integer.MAX_VALUE + 200, selection.step(original, 100));
        assertEquals((long) Integer.MAX_VALUE + 200, original.getCount());
        assertEquals(Long.MAX_VALUE, selection.step(original, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, selection.step(original, 1));
        assertEquals(1L, selection.step(original, Long.MIN_VALUE));
        assertEquals(1L, selection.step(original, -1));
        assertEquals(1L, original.getCount());
        assertSame(original, selection.getItems().get(0));
    }

    @Test
    void setAllClampsCountsWithoutReplacingItemsOrChangingOrder() {
        PickerSelection<Entry> selection = new PickerSelection<>();
        Entry first = new Entry("a", 2);
        Entry second = new Entry("b", 3);
        selection.getItems().addAll(List.of(first, second));

        selection.setAll(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, first.getCount());
        assertEquals(Long.MAX_VALUE, second.getCount());
        selection.setAll(0);
        assertEquals(1L, first.getCount());
        assertEquals(1L, second.getCount());
        selection.setAll(Long.MIN_VALUE);
        assertEquals(1L, first.getCount());
        assertEquals(1L, second.getCount());
        selection.setAll((long) Integer.MAX_VALUE + 1);
        assertEquals((long) Integer.MAX_VALUE + 1, first.getCount());
        assertEquals((long) Integer.MAX_VALUE + 1, second.getCount());
        assertEquals(List.of(first, second), selection.getItems());
    }

    @Test
    void setAllOnEmptySelectionKeepsItEmpty() {
        PickerSelection<Entry> selection = new PickerSelection<>();
        selection.setAll(Long.MAX_VALUE);
        assertTrue(selection.getItems().isEmpty());
    }

    @Test
    void clampCountPreservesLongPrecisionAndEnforcesLowerBound() {
        assertEquals(1L, PickerSelection.clampCount(Long.MIN_VALUE));
        assertEquals(1L, PickerSelection.clampCount(-1));
        assertEquals(1L, PickerSelection.clampCount(0));
        assertEquals(1L, PickerSelection.clampCount(1));
        assertEquals((long) Integer.MAX_VALUE + 1, PickerSelection.clampCount((long) Integer.MAX_VALUE + 1));
        assertEquals(Long.MAX_VALUE, PickerSelection.clampCount(Long.MAX_VALUE));
    }

    @Test
    void addClampHandlesExactLimitsAndSignedOverflow() {
        assertEquals(Long.MAX_VALUE, PickerSelection.addClamp(Long.MAX_VALUE - 2, 2));
        assertEquals(Long.MAX_VALUE, PickerSelection.addClamp(Long.MAX_VALUE - 2, 3));
        assertEquals(Long.MAX_VALUE, PickerSelection.addClamp(1, Long.MAX_VALUE));
        assertEquals(1L, PickerSelection.addClamp(Long.MAX_VALUE, Long.MIN_VALUE));
        assertEquals(1L, PickerSelection.addClamp(1, Long.MIN_VALUE));
        assertEquals(1L, PickerSelection.addClamp(Long.MIN_VALUE, -1));
        assertEquals(1L, PickerSelection.addClamp(0, 0));
        assertEquals(10L, PickerSelection.addClamp(-10, 20));
        assertEquals(9L, PickerSelection.addClamp(10, -1));
    }

    @Test
    void mulClampPreservesExactProductsAndSaturatesBeforeOverflow() {
        assertEquals(42L, PickerSelection.mulClamp(6, 7));
        assertEquals(Long.MAX_VALUE - 1, PickerSelection.mulClamp(Long.MAX_VALUE / 2, 2));
        assertEquals(Long.MAX_VALUE, PickerSelection.mulClamp(Long.MAX_VALUE / 2 + 1, 2));
        assertEquals(Long.MAX_VALUE, PickerSelection.mulClamp(2, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, PickerSelection.mulClamp(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, PickerSelection.mulClamp(Long.MAX_VALUE, 1));
        assertEquals(1L, PickerSelection.mulClamp(0, Long.MAX_VALUE));
        assertEquals(1L, PickerSelection.mulClamp(Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    void mulClampIgnoresNonpositiveFactorsAsThePickerAlreadyDoes() {
        assertEquals(7L, PickerSelection.mulClamp(7, 0));
        assertEquals(7L, PickerSelection.mulClamp(7, -1));
        assertEquals(7L, PickerSelection.mulClamp(7, Long.MIN_VALUE));
        assertEquals(1L, PickerSelection.mulClamp(Long.MIN_VALUE, -1));
        assertEquals(1L, PickerSelection.mulClamp(0, 0));
    }

    private static final class Entry implements PickerItem {
        private final String id;
        private final String tag;
        private long count;

        private Entry(String id, long count) {
            this(id, "", count);
        }

        private Entry(String id, String tag, long count) {
            this.id = id;
            this.tag = tag;
            this.count = count;
        }

        @Override
        public long getCount() {
            return count;
        }

        @Override
        public void setCount(long count) {
            this.count = count;
        }
    }
}
