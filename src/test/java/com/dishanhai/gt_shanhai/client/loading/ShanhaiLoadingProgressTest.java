package com.dishanhai.gt_shanhai.client.loading;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 山海：使用原集合真正處理資料，驗證中間計數與回呼順序。 */
class ShanhaiLoadingProgressTest {
    @Test void cancelledVanillaBodyStillTracksKubeJsStyleTraversalIncludingSkippedFiles() {
        var source = new LinkedHashMap<String, Integer>();
        source.put("a", 1); source.put("_skipped", 2); source.put("bad", 3); source.put("d", 4);
        var state = new ShanhaiLoadingState(); long session = state.begin(0);
        var handled = new ArrayList<String>(); var counts = new ArrayList<Integer>();
        var tracked = ShanhaiLoadingProgress.trackMap(source, entry -> {
            assertTrue(handled.contains(entry.getKey()), "只能回報已走完的候選");
            int current = counts.size() + 1; counts.add(current);
            state.update(session, "recipes", "files", entry.getKey(), current, source.size());
            if (current == 2) assertEquals(.5f, state.snapshot(0, false).tasks().getFirst().fraction());
        });
        for (var entry : tracked.entrySet()) {
            handled.add(entry.getKey());
            if (entry.getKey().startsWith("_")) continue;
            try { if (entry.getKey().equals("bad")) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException ignored) { /* 原流程自行記錄失敗後繼續。 */ }
        }
        assertEquals(List.of(1, 2, 3, 4), counts);
        assertEquals(List.copyOf(source.keySet()), handled);
    }

    @Test void replacementForEachReportsAfterApplicationAndStopsOnOriginalException() {
        var applied = new ArrayList<Integer>(); var progress = new ArrayList<Integer>();
        var tracked = ShanhaiLoadingProgress.track(List.of(1, 2, 3, 4), value -> {
            assertTrue(applied.contains(value)); progress.add(value);
        });
        var failure = new IllegalStateException("fixture");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> tracked.forEach(value -> {
            if (value == 3) throw failure;
            applied.add(value);
        })));
        assertEquals(List.of(1, 2), applied);
        assertEquals(applied, progress);
    }

    @Test void mapRemainsLiveMutableAndEntryForEachStillReportsEveryItem() {
        var source = new LinkedHashMap<String, Integer>(); source.put("a", 1);
        var seen = new ArrayList<String>();
        var tracked = ShanhaiLoadingProgress.trackMap(source, entry -> seen.add(entry.getKey()));
        tracked.put("b", 2); source.put("c", 3);
        var handled = new ArrayList<String>();
        tracked.entrySet().forEach(entry -> handled.add(entry.getKey()));
        assertEquals(List.of("a", "b", "c"), handled);
        assertEquals(handled, seen);
        var iterator = tracked.entrySet().iterator();
        iterator.next(); iterator.remove();
        assertFalse(source.containsKey("a"));
        assertEquals(2, tracked.size());
        tracked.clear(); assertTrue(source.isEmpty());
    }

    @Test void repeatedHasNextDoesNotDoubleCountAndForEachRemainingKeepsOrder() {
        var reports = new ArrayList<Integer>();
        var iterator = ShanhaiLoadingProgress.track(List.of(1, 2, 3), reports::add).iterator();
        assertTrue(iterator.hasNext()); assertTrue(iterator.hasNext()); assertTrue(reports.isEmpty());
        assertEquals(1, iterator.next()); assertTrue(reports.isEmpty());
        assertTrue(iterator.hasNext()); assertTrue(iterator.hasNext());
        assertEquals(List.of(1), reports);
        var rest = new ArrayList<Integer>(); iterator.forEachRemaining(rest::add);
        assertEquals(List.of(2, 3), rest); assertEquals(List.of(1, 2, 3), reports);
        assertFalse(iterator.hasNext()); assertEquals(3, reports.size());
    }

    @Test void cancelledSessionRejectsTrackedCallbacksAndUnknownTerrainIsNotInvented() {
        var state = new ShanhaiLoadingState(); long old = state.begin(0);
        var tracked = ShanhaiLoadingProgress.track(List.of(1, 2), count -> state.update(old, "sync", "sync", "", count, 2));
        state.reset(); long next = state.begin(1);
        tracked.forEach(value -> {});
        state.completeExisting(old, "terrain"); state.completeExisting(next, "terrain");
        assertTrue(state.snapshot(2, false).tasks().isEmpty());
        state.update("terrain", "spawn", "", 43, 100);
        state.completeExisting(next, "terrain");
        var terrain = state.snapshot(3, false).tasks().getFirst();
        assertTrue(terrain.done()); assertEquals(100, terrain.current()); assertEquals(1f, terrain.fraction());
    }

    @Test void mapProgressDoesNotReadOrCopyAllValuesBeforeProcessing() {
        var reads = new AtomicInteger();
        Map<Integer, Integer> source = new java.util.AbstractMap<>() {
            @Override public java.util.Set<Entry<Integer, Integer>> entrySet() {
                return new java.util.AbstractSet<>() {
                    @Override public int size() { return 1000; }
                    @Override public java.util.Iterator<Entry<Integer, Integer>> iterator() {
                        return java.util.stream.IntStream.range(0, 1000).mapToObj(i -> {
                            reads.incrementAndGet(); return Map.entry(i, i);
                        }).iterator();
                    }
                };
            }
        };
        var tracked = ShanhaiLoadingProgress.trackMap(source, value -> {});
        assertEquals(0, reads.get());
        var iterator = tracked.entrySet().iterator(); iterator.next();
        assertEquals(1, reads.get());
    }
}
