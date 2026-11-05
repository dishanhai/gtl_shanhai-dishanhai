package com.dishanhai.gt_shanhai.integration.enhancedcore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 山海：輸入按條目分帳；實料相加，在場鏡像取最大值。 */
public final class EnhancedCoreCloudInputs {

    private EnhancedCoreCloudInputs() {}

    public record Input<K>(K key, long amount, boolean presence) {}

    public record Split<K>(Map<K, Long> physical, Map<K, Long> presence) {
        public Split {
            physical = Collections.unmodifiableMap(new LinkedHashMap<>(physical));
            presence = Collections.unmodifiableMap(new LinkedHashMap<>(presence));
        }

        /** 返回可供外部執行器繼續增補的副本，不改變來源配置或本次分解。 */
        public Map<K, Long> mergePresence(Map<K, Long> configured) {
            Map<K, Long> result = new LinkedHashMap<>(configured);
            presence.forEach((key, amount) -> result.merge(key, amount, Math::max));
            return result;
        }
    }

    public static <K> Split<K> split(Iterable<Input<K>> inputs) {
        Map<K, Long> physical = new LinkedHashMap<>();
        Map<K, Long> presence = new LinkedHashMap<>();
        for (Input<K> input : inputs) {
            Objects.requireNonNull(input.key());
            if (input.amount() < 1) throw new IllegalArgumentException();
            if (input.presence()) presence.merge(input.key(), input.amount(), Math::max);
            else physical.merge(input.key(), input.amount(), Math::addExact);
        }
        return new Split<>(physical, presence);
    }
}
