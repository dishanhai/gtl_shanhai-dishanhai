package com.dishanhai.gt_shanhai.common.machine.primordial;

// Author: dishanhai
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 山海：只配置正在運行的執行緒；所有空閒執行緒共用一次惰性搜尋。 */
public final class PrimordialThreadScheduler<T> {
    public record Lane<T>(long index, long reserved, T task, int threadCount) {
        public Lane(long index, long reserved, T task) { this(index, reserved, task, 1); }
    }
    public interface Runner<T> {
        boolean advance(T task);
        T start(long allowance);

        /** 提供已啟動批次的實際並行量。無實績時保留完整額度。 */
        default long reservation(T task, long allowance) { return allowance; }
        default int admissionLimit() { return 1; }
        default boolean merge(T existing, T incoming) { return false; }
    }

    private final List<Lane<T>> lanes = new ArrayList<>();
    private final List<Lane<T>> laneView = Collections.unmodifiableList(lanes);
    private int activeThreads;
    private long reservedParallel;
    private long nextSearch = Long.MIN_VALUE;
    private long lastSearch = Long.MIN_VALUE;
    private boolean lastSearchSucceeded;
    private boolean dirty = true;
    private boolean pushed;
    private boolean enabled = true;

    public static long threadCount(long additional) {
        return additional >= Long.MAX_VALUE - 1L ? Long.MAX_VALUE : 1L + Math.max(0L, additional);
    }

    public List<Lane<T>> lanes() { return laneView; }
    public int activeThreads() { return activeThreads; }
    /** 明確的新訂單／設定變更：下一 tick 接單，不沿用舊輸入的失敗退避。 */
    public void requestSearch() { dirty = true; pushed = true; }
    /** 一般倉室數量通知可能每 tick 發生，保留失敗限頻。 */
    public void requestRetry() { dirty = true; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long reserved() {
        if (reservedParallel >= 0L) return reservedParallel;
        long total = 0L;
        for (Lane<T> lane : lanes) {
            total += Math.min(lane.reserved, Long.MAX_VALUE - total);
            if (total == Long.MAX_VALUE) break;
        }
        reservedParallel = total;
        return reservedParallel;
    }

    private void addReservation(long reserved) {
        if (reservedParallel >= 0L) reservedParallel += Math.min(reserved, Long.MAX_VALUE - reservedParallel);
    }

    /** 讀檔接續已扣料批次；不受當前降配影響。 */
    public void restore(long index, long reserved, T task) {
        restore(index, reserved, task, 1);
    }

    public void restore(long index, long reserved, T task, int threadCount) {
        if (task == null || index < 0 || reserved < 0
                || threadCount < 1 || threadCount > 4
                || lanes.stream().anyMatch(lane -> lane.index == index)) {
            throw new IllegalArgumentException("Invalid persisted primordial recipe thread");
        }
        lanes.add(new Lane<>(index, reserved, task, threadCount));
        activeThreads += threadCount;
        addReservation(reserved);
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
        int active = activeThreads();
        if (count <= 0L || active >= count) return 0L;
        if (independentRecipePools) return capacity;
        long remaining = capacity - Math.min(capacity, reserved());
        if (tracksActualParallel) {
            // 有限執行緒均分尚未占用的額度。無限執行緒則由配方需求決定當批大小。
            return threads == Long.MAX_VALUE ? remaining : Math.ceilDiv(remaining, count - active);
        }
        long index = nextIndex();
        if (index >= count) return 0L;
        long share = capacity / count + (index < capacity % count ? 1L : 0L);
        // 山海：保留已扣料批次的預留，不足完整份額時仍讓空閒執行緒使用餘額。
        return Math.min(share, remaining);
    }

    public void tick(long now, long capacity, long threads, Runner<T> runner) {
        tick(now, capacity, threads, false, runner);
    }

    public void tick(long now, long capacity, long threads, boolean tracksActualParallel, Runner<T> runner) {
        tick(now, capacity, threads, tracksActualParallel, false, runner);
    }

    boolean isSearchDue(long now) {
        if (lastSearch == now) return false;
        // 成功工作可以逐 tick 接續（分子組裝原生為 1 tick），只有失敗後才限頻。
        if (!pushed && !lastSearchSucceeded && lastSearch != Long.MIN_VALUE
                && now >= lastSearch && now - lastSearch < 5L) return false;
        return dirty || now >= nextSearch;
    }

    public void tick(long now, long capacity, long threads, boolean tracksActualParallel,
                     boolean independentRecipePools, Runner<T> runner) {
        if (!enabled) return;
        // 必須先讓本 tick 開始時已有的批次各運行一次，新批次從下一 tick 起計時。
        for (var iterator = lanes.iterator(); iterator.hasNext();) {
            Lane<T> lane = iterator.next();
            if (runner.advance(lane.task)) {
                iterator.remove();
                activeThreads -= lane.threadCount;
                // 饱和后无法直接减回准确值，等下次读取时按剩余批次重算。
                reservedParallel = reservedParallel < 0L || reservedParallel == Long.MAX_VALUE
                        ? -1L : reservedParallel - lane.reserved;
                dirty = true;
                if (independentRecipePools) pushed = true; // 已結算的批次可能釋出原先用滿的配方池。
            }
        }
        if (!isSearchDue(now)) return;
        long share = admissionShare(capacity, threads, tracksActualParallel, independentRecipePools);
        if (share <= 0L) return;

        dirty = false;
        pushed = false;
        lastSearch = now;
        nextSearch = now > Long.MAX_VALUE - 20L ? Long.MAX_VALUE : now + 20L;
        lastSearchSucceeded = false;
        int limit = Math.clamp(runner.admissionLimit(), 1, 96);
        for (int attempt = 0; attempt < limit && share > 0L; attempt++) {
            T task = runner.start(share);
            if (task == null) break;
            lastSearchSucceeded = true;
            long reservation = tracksActualParallel
                    ? Math.clamp(runner.reservation(task, share), 1L, share) : share;
            boolean merged = false;
            for (int i = 0; i < lanes.size(); i++) {
                Lane<T> lane = lanes.get(i);
                if (lane.threadCount < 4 && (independentRecipePools || reservation <= Long.MAX_VALUE - lane.reserved)
                        && runner.merge(lane.task, task)) {
                    long combined = lane.reserved + Math.min(reservation, Long.MAX_VALUE - lane.reserved);
                    lanes.set(i, new Lane<>(lane.index, combined, lane.task, lane.threadCount + 1));
                    activeThreads++;
                    addReservation(combined - lane.reserved);
                    merged = true;
                    break;
                }
            }
            if (!merged) restore(nextIndex(), reservation, task);
            dirty = true;
            share = admissionShare(capacity, threads, tracksActualParallel, independentRecipePools);
        }
    }
}
