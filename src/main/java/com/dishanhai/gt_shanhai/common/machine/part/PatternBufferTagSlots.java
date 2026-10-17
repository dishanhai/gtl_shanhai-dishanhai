package com.dishanhai.gt_shanhai.common.machine.part;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把标签匹配填进空的库存槽。已有手动配置的槽不改，上一轮自动填入的槽优先留在原位。
 */
public final class PatternBufferTagSlots {

    private PatternBufferTagSlots() {}

    public static <T> List<T> assign(List<T> manual, List<T> previousAutomatic, List<T> matches) {
        int capacity = manual.size();
        List<T> next = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) {
            next.add(null);
        }
        Set<T> used = new HashSet<>();
        Set<T> wanted = new HashSet<>(matches);
        for (int i = 0; i < capacity; i++) {
            if (manual.get(i) != null) {
                continue;
            }
            T previous = i < previousAutomatic.size() ? previousAutomatic.get(i) : null;
            if (previous != null && wanted.contains(previous)) {
                next.set(i, previous);
                used.add(previous);
            }
        }
        int cursor = 0;
        for (T match : matches) {
            if (match == null || used.contains(match) || manual.contains(match)) {
                continue;
            }
            while (cursor < capacity && (manual.get(cursor) != null || next.get(cursor) != null)) {
                cursor++;
            }
            if (cursor >= capacity) {
                break;
            }
            next.set(cursor, match);
            used.add(match);
            cursor++;
        }
        return next;
    }
}
