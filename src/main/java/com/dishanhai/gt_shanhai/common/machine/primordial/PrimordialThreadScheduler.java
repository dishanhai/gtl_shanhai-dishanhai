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

        /** 提供已啟動批次的實際並行量。無實績時保留完整額度。 */
        default long reservation(T task, long allowance) { return allowance; }
    }

    private final List<Lane<T>> lanes = new ArrayList<>();
    private long nextSearch = Long.MIN_VALUE;
    private long lastSearch = Long.MIN_VALUE;
    private boolean lastSearchSucceeded;
    private boolean dirty = true;
    private boolean pushed;
    private boolean enabled = true;

    public static long threadCount(long additional) {
        return additional >= Long.MAX_VALUE - 1L ? Long.MAX_VALUE : 1L + Math.max(0L, additional);
    }

    public List<Lane<T>> lanes() { return Collections.unmodifiableList(lanes); }
    /** 明確的新訂單／設定變更：下一 tick 接單，不沿用舊輸入的失敗退避。 */
    public void requestSearch() { dirty = true; pushed = true; }
    /** 一般倉室數量通知可能每 tick 發生，保留失敗限頻。 */
    public void requestRetry() { dirty = true; }
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

    private long nextIndex() {
        long index = 0L;
        for (Lane<T> lane : lanes) {
            if (lane.index == index) index++;
            else if (lane.index > index) break;
        }
        return index;
    }

    /** 唯讀的接單額度；供排程與告警共同判斷，包含降配後保留的舊預留。 */
    public long admissionShare(long capacity, long threads) {
        return admissionShare(capacity, threads, false);
    }

    public long admissionShare(long capacity, long threads, boolean tracksActualParallel) {
        return admissionShare(capacity, threads, tracksActualParallel, false);
    }

    public long admissionShare(long capacity, long threads, boolean tracksActualParallel, boolean independentRecipePools) {
        long count = Math.min(Math.max(0L, capacity), Math.max(1L, threads));
        if (count <= 0L || lanes.size() >= count) return 0L;
        if (independentRecipePools) return capacity;
        long remaining = capacity - Math.min(capacity, reserved());
        if (tracksActualParallel) {
            // 有限執行緒均分尚未占用的額度。無限執行緒則由配方需求決定當批大小。
            return threads == Long.MAX_VALUE ? remaining : Math.ceilDiv(remaining, count - lanes.size());
        }
        long index = nextIndex();
        if (index >= count) return 0L;
        long share = capacity / count + (index < capacity % count ? 1L : 0L);
        return share > remaining ? 0L : share;
    }

    public void tick(long now, long capacity, long threads, Runner<T> runner) {
        tick(now, capacity, threads, false, runner);
    }

    public void tick(long now, long capacity, long threads, boolean tracksActualParallel, Runner<T> runner) {
        tick(now, capacity, threads, tracksActualParallel, false, runner);
    }

    public void tick(long now, long capacity, long threads, boolean tracksActualParallel,
                     boolean independentRecipePools, Runner<T> runner) {
        if (!enabled) return;
        // 必須先讓本 tick 開始時已有的批次各運行一次，新批次從下一 tick 起計時。
        for (var iterator = lanes.iterator(); iterator.hasNext();) {
            if (runner.advance(iterator.next().task)) {
                iterator.remove();
                dirty = true;
                if (independentRecipePools) pushed = true; // 已結算的批次可能釋出原先用滿的配方池。
            }
        }
        long share = admissionShare(capacity, threads, tracksActualParallel, independentRecipePools);
        if (share <= 0L) return;
        if (lastSearch == now) return;
        // 成功工作可以逐 tick 接續（分子組裝原生為 1 tick），只有失敗後才限頻。
        if (!pushed && !lastSearchSucceeded && lastSearch != Long.MIN_VALUE
                && now >= lastSearch && now - lastSearch < 5L) return;
        if (!dirty && now < nextSearch) return;

        long index = nextIndex();

        dirty = false;
        pushed = false;
        lastSearch = now;
        nextSearch = now > Long.MAX_VALUE - 20L ? Long.MAX_VALUE : now + 20L;
        T task = runner.start(share);
        lastSearchSucceeded = task != null;
        if (task != null) {
            long reservation = tracksActualParallel
                    ? Math.clamp(runner.reservation(task, share), 1L, share) : share;
            restore(index, reservation, task);
            dirty = true;
        }
    }
}
