package com.dishanhai.gt_shanhai.client.gui.scaled;

import java.util.HashMap;
import java.util.Map;

/**
 * 按 key 管一批同预设的弹簧，hover 与错峰入场用。新项初值 0。
 * 长时间没被读过的项由 {@link #sweep} 清掉；容量封顶，超出时整体清空（和 {@code loadingCellAtMs} 的做法一致）。
 */
public final class SpringTable<K> {

    public static final int CAPACITY = 512;
    private static final long IDLE_MS = 1_500L;

    private static final class Entry {
        final Spring spring;
        long lastUsedMs;

        Entry(Spring spring, long lastUsedMs) {
            this.spring = spring;
            this.lastUsedMs = lastUsedMs;
        }
    }

    private final Spring.Preset preset;
    private final Map<K, Entry> map = new HashMap<>();

    public SpringTable(Spring.Preset preset) {
        this.preset = preset;
    }

    /** 取该 key 的弹簧，没有就建（初值 0）。 */
    public Spring get(K key, long nowMs) {
        Entry e = map.get(key);
        if (e == null) {
            if (map.size() >= CAPACITY) map.clear();
            e = new Entry(new Spring(preset), nowMs);
            map.put(key, e);
        }
        e.lastUsedMs = nowMs;
        return e.spring;
    }

    /** 目标改成 {@code target}，推进到 {@code nowMs}，返回当前值。目标没变时不重设。 */
    public float follow(K key, float target, long nowMs) {
        Spring s = get(key, nowMs);
        if (s.target() != target) s.setTarget(target, nowMs);
        s.update(nowMs);
        return s.value();
    }

    /** 新建（覆盖旧的）一根从 {@code from} 出发、延迟 {@code delayMs} 后走向 {@code to} 的弹簧。 */
    public void start(K key, float from, float to, long nowMs, long delayMs) {
        if (!map.containsKey(key) && map.size() >= CAPACITY) map.clear();
        Spring s = new Spring(preset, from);
        s.setTargetAfter(to, nowMs, delayMs);
        map.put(key, new Entry(s, nowMs));
    }

    /** 有这根弹簧就推进并返回它的值，没有返回 {@code absent}（错峰：没登记的格子按已到位处理）。 */
    public float valueOr(K key, float absent, long nowMs) {
        Entry e = map.get(key);
        if (e == null) return absent;
        e.lastUsedMs = nowMs;
        e.spring.update(nowMs);
        return e.spring.value();
    }

    public boolean contains(K key) {
        return map.containsKey(key);
    }

    /** 清掉超过 1.5 秒没被读过的项。 */
    public void sweep(long nowMs) {
        if (map.isEmpty()) return;
        map.values().removeIf(e -> nowMs - e.lastUsedMs > IDLE_MS);
    }

    public int size() {
        return map.size();
    }

    public void clear() {
        map.clear();
    }
}
