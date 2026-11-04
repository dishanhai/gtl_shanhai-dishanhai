package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 山海：只配置正在運行的執行緒；所有空閒執行緒共用一次惰性搜尋。 */
public final class PrimordialThreadScheduler<T> {
    public record Lane<T>(long index, long reserved, T task) {}
    public interface Runner<T> {
        boolean advance(T task);
        T start(long allowance);
    }

    private final List<Lane<T>> lanes = new ArrayList<>();
    private long nextSearch = Long.MIN_VALUE;
    private long lastSearch = Long.MIN_VALUE;
    private boolean lastSearchSucceeded;
    private boolean dirty = true;
    private boolean enabled = true;

    public static long threadCount(long additional) {
        return additional >= Long.MAX_VALUE - 1L ? Long.MAX_VALUE : 1L + Math.max(0L, additional);
    }

    public List<Lane<T>> lanes() { return Collections.unmodifiableList(lanes); }
    public void requestSearch() { dirty = true; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long reserved() {
        long total = 0L;
        for (Lane<T> lane : lanes) {
            if (lane.reserved > Long.MAX_VALUE - total) return Long.MAX_VALUE;
            total += lane.reserved;
        }
        return total;
    }

    /** 讀檔接續已扣料批次；不受當前降配影響。 */
    public void restore(long index, long reserved, T task) {
        if (task == null || index < 0 || reserved < 0
                || lanes.stream().anyMatch(lane -> lane.index == index)) {
            throw new IllegalArgumentException("Invalid persisted primordial recipe thread");
        }
        lanes.add(new Lane<>(index, reserved, task));
        lanes.sort(Comparator.comparingLong(Lane::index));
    }

    public void tick(long now, long capacity, long threads, Runner<T> runner) {
        if (!enabled) return;
        // 必須先讓本 tick 開始時已有的批次各運行一次，新批次從下一 tick 起計時。
        for (var iterator = lanes.iterator(); iterator.hasNext();) {
            if (runner.advance(iterator.next().task)) {
                iterator.remove();
                dirty = true;
            }
        }
        long count = Math.min(Math.max(0L, capacity), Math.max(1L, threads));
        if (count <= 0L || lanes.size() >= count) return;
        if (lastSearch == now) return;
        // 成功工作可以逐 tick 接續（分子組裝原生為 1 tick），只有失敗後才限頻。
        if (!lastSearchSucceeded && lastSearch != Long.MIN_VALUE
                && now >= lastSearch && now - lastSearch < 5L) return;
        if (!dirty && now < nextSearch) return;

        long index = 0L;
        for (Lane<T> lane : lanes) {
            if (lane.index == index) index++;
            else if (lane.index > index) break;
        }
        if (index >= count) return;
        long share = capacity / count + (index < capacity % count ? 1L : 0L);
        // 容量或執行緒數縮小後，保留舊批次但不再超額接單。
        if (share > capacity - Math.min(capacity, reserved())) return;

        dirty = false;
        lastSearch = now;
        nextSearch = now > Long.MAX_VALUE - 20L ? Long.MAX_VALUE : now + 20L;
        T task = runner.start(share);
        lastSearchSucceeded = task != null;
        if (task != null) {
            restore(index, share, task);
            dirty = true;
        }
    }
}
