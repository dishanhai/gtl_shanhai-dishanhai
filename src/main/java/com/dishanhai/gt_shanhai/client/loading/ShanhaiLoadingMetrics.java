package com.dishanhai.gt_shanhai.client.loading;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.LongSupplier;

/** 山海：有界且執行緒安全的載入效能數據；並行工作耗時不當作階段總耗時。 */
public final class ShanhaiLoadingMetrics {
    private static final int MAX_ENTRIES = 512;
    private static final Comparator<Entry> LONGEST = Comparator.comparingLong(Entry::elapsedNanos).reversed();
    private final String phase;
    private final LongSupplier clock;
    private final long started;
    private final long initialHeap;
    private final LinkedHashMap<String, MutableEntry> entries = new LinkedHashMap<>();
    private final PriorityQueue<Entry> slowRecipes = new PriorityQueue<>(Comparator.comparingLong(Entry::elapsedNanos));
    private long peakHeap;
    private long pausedAt = -1;
    private long pausedNanos;
    private Report completed;

    public ShanhaiLoadingMetrics(String phase, LongSupplier clock, long initialHeap) {
        this.phase = phase;
        this.clock = clock;
        this.started = clock.getAsLong();
        this.initialHeap = initialHeap;
        this.peakHeap = initialHeap;
    }

    public synchronized void record(String name, String kind, long elapsed, long cpu, long allocated, long heap, boolean failed) {
        if (completed != null) return;
        String key = kind + '\n' + name;
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(key)) { key = "overflow"; name = "其他項目（超過統計上限）"; kind = "aggregate"; }
        MutableEntry entry = entries.get(key);
        if (entry == null) { entry = new MutableEntry(name, kind); entries.put(key, entry); }
        entry.calls++;
        entry.elapsed += Math.max(0, elapsed);
        if (cpu >= 0) { entry.cpu += cpu; entry.cpuSamples++; }
        if (allocated >= 0) { entry.allocated += allocated; entry.allocationSamples++; }
        if (failed) entry.failures++;
        peakHeap = Math.max(peakHeap, heap);
    }

    public synchronized void slowRecipe(String id, long elapsed, long cpu, long allocated, long heap, boolean failed) {
        if (completed != null || slowRecipes.size() == 32 && elapsed <= slowRecipes.peek().elapsedNanos()) return;
        if (slowRecipes.size() == 32) slowRecipes.poll();
        slowRecipes.add(new Entry(id, "recipe", 1, Math.max(0, elapsed), Math.max(0, cpu), cpu >= 0 ? 1 : 0,
                Math.max(0, allocated), allocated >= 0 ? 1 : 0, failed ? 1 : 0));
        peakHeap = Math.max(peakHeap, heap);
    }

    public synchronized void pause() { if (completed == null && pausedAt < 0) pausedAt = clock.getAsLong(); }
    public synchronized void resume() {
        if (completed == null && pausedAt >= 0) { pausedNanos += clock.getAsLong() - pausedAt; pausedAt = -1; }
    }

    public synchronized Report snapshot(String outcome, long heap) {
        if (completed != null) return completed;
        long now = pausedAt >= 0 ? pausedAt : clock.getAsLong();
        var rows = new ArrayList<Entry>();
        for (var entry : entries.values()) rows.add(entry.freeze());
        rows.sort(LONGEST);
        var recipes = new ArrayList<>(slowRecipes); recipes.sort(LONGEST);
        return new Report(phase, outcome, Math.max(0, now - started - pausedNanos), initialHeap,
                Math.max(peakHeap, heap), heap - initialHeap, List.copyOf(rows), List.copyOf(recipes));
    }

    public synchronized Report finish(String outcome, long heap) {
        if (completed == null) completed = snapshot(outcome, heap);
        return completed;
    }

    public record Entry(String name, String kind, long calls, long elapsedNanos, long cpuNanos, long cpuSamples,
                        long allocatedBytes, long allocationSamples, long failures) {}
    public record Report(String phase, String outcome, long elapsedNanos, long initialHeapBytes,
                         long peakObservedHeapBytes, long heapDeltaBytes, List<Entry> entries, List<Entry> slowRecipes) {}

    private static final class MutableEntry {
        private final String name, kind;
        private long calls, elapsed, cpu, cpuSamples, allocated, allocationSamples, failures;
        private MutableEntry(String name, String kind) { this.name = name; this.kind = kind; }
        private Entry freeze() { return new Entry(name, kind, calls, elapsed, cpu, cpuSamples, allocated, allocationSamples, failures); }
    }
}
