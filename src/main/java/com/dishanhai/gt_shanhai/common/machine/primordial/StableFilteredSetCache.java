package com.dishanhai.gt_shanhai.common.machine.primordial;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

final class StableFilteredSetCache<T> {

    private Set<T> cached;

    Set<T> get() {
        return cached;
    }

    void clear() {
        cached = null;
    }

    void set(Set<T> values) {
        cached = values == null || values.isEmpty() ? null : values;
    }

    Set<T> retainIf(Predicate<T> predicate) {
        Set<T> current = cached;
        if (current == null || current.isEmpty()) {
            cached = null;
            return null;
        }

        LinkedHashSet<T> filtered = null;
        for (T value : current) {
            if (predicate.test(value)) {
                if (filtered != null) {
                    filtered.add(value);
                }
            } else {
                if (filtered == null) {
                    filtered = new LinkedHashSet<>();
                    for (T previous : current) {
                        if (previous == value) {
                            break;
                        }
                        filtered.add(previous);
                    }
                }
            }
        }

        if (filtered == null) {
            return current;
        }

        cached = filtered.isEmpty() ? null : filtered;
        return cached;
    }
}
