package com.dishanhai.gt_shanhai.common.item.generator;

import java.util.function.ToIntFunction;

/** The whitelist bit positions are raw registry IDs, not dense entry indices. */
public final class PatternGeneratorRegistryBits {
    private PatternGeneratorRegistryBits() {}

    public static <T> int exclusiveIdLimit(Iterable<T> entries, ToIntFunction<? super T> registryId) {
        int maxId = -1;
        for (T entry : entries) maxId = Math.max(maxId, registryId.applyAsInt(entry));
        return Math.addExact(maxId, 1);
    }
}
