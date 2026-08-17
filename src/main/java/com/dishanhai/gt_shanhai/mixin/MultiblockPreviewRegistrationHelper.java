package com.dishanhai.gt_shanhai.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

final class MultiblockPreviewRegistrationHelper {

    private MultiblockPreviewRegistrationHelper() {
    }

    static <S, T> List<T> collect(Iterable<S> sources, Predicate<? super S> selected,
                                  Function<? super S, ? extends T> factory,
                                  BiConsumer<? super S, ? super Throwable> onFailure) {
        List<T> results = new ArrayList<>();
        for (S source : sources) {
            try {
                if (!selected.test(source)) {
                    continue;
                }
                T result = factory.apply(source);
                if (result != null) {
                    results.add(result);
                }
            } catch (RuntimeException | LinkageError error) {
                onFailure.accept(source, error);
            }
        }
        return results;
    }
}
