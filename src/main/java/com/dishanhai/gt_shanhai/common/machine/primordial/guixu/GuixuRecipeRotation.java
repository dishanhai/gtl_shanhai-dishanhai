package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/** 山海：按類型輪轉候選，讓有限執行緒仍能輪到後面的配方類型。 */
public final class GuixuRecipeRotation {
    private GuixuRecipeRotation() {}

    public static <T> LinkedHashSet<T> roundRobin(List<? extends Iterable<T>> groups) {
        List<Iterator<T>> iterators = groups.stream().map(Iterable::iterator).toList();
        var result = new LinkedHashSet<T>();
        boolean progressed;
        do {
            progressed = false;
            for (var iterator : iterators) {
                while (iterator.hasNext()) {
                    if (result.add(iterator.next())) {
                        progressed = true;
                        break;
                    }
                }
            }
        } while (progressed);
        return result;
    }
}
