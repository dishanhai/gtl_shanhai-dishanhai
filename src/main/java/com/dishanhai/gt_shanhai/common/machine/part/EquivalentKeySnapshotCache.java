package com.dishanhai.gt_shanhai.common.machine.part;

import com.dishanhai.gt_shanhai.api.ae2.AeStorageAmountMath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

final class EquivalentKeySnapshotCache<K> {

    static final class Entry<K> {
        private final K key;
        private final long amount;

        Entry(K key, long amount) {
            this.key = key;
            this.amount = amount;
        }

        K key() {
            return key;
        }

        long amount() {
            return amount;
        }
    }

    private final Function<K, K> normalizer;
    private Snapshot<K> snapshot;

    EquivalentKeySnapshotCache(Function<K, K> normalizer) {
        this.normalizer = normalizer;
    }

    synchronized long getNormalizedAmount(K key, Supplier<? extends Iterable<Entry<K>>> loader) {
        return ensureSnapshot(loader).getNormalizedAmount(normalizer.apply(key));
    }

    synchronized List<K> getEquivalentRawKeys(K key, Supplier<? extends Iterable<Entry<K>>> loader) {
        return ensureSnapshot(loader).getEquivalentRawKeys(normalizer.apply(key));
    }

    synchronized void replace(Iterable<Entry<K>> entries) {
        snapshot = Snapshot.from(entries, normalizer);
    }

    synchronized void invalidate() {
        snapshot = null;
    }

    synchronized boolean hasSnapshot() {
        return snapshot != null;
    }

    synchronized void forEachNormalized(NormalizedEntryConsumer<K> consumer) {
        if (snapshot != null) {
            snapshot.forEachNormalized(consumer);
        }
    }

    private Snapshot<K> ensureSnapshot(Supplier<? extends Iterable<Entry<K>>> loader) {
        if (snapshot == null) {
            snapshot = Snapshot.from(loader.get(), normalizer);
        }
        return snapshot;
    }

    interface NormalizedEntryConsumer<K> {
        void accept(K key, long amount);
    }

    private static final class Snapshot<K> {
        private final Map<K, Long> normalizedAmounts = new LinkedHashMap<>();
        private final Map<K, ArrayList<K>> rawKeysByNormalized = new LinkedHashMap<>();

        private static <K> Snapshot<K> from(Iterable<Entry<K>> entries, Function<K, K> normalizer) {
            Snapshot<K> snapshot = new Snapshot<>();
            for (Entry<K> entry : entries) {
                if (entry == null || entry.key() == null || entry.amount() <= 0L) {
                    continue;
                }
                snapshot.add(entry.key(), normalizer.apply(entry.key()), entry.amount());
            }
            return snapshot;
        }

        private long getNormalizedAmount(K normalizedKey) {
            Long amount = normalizedAmounts.get(normalizedKey);
            return amount != null ? amount.longValue() : 0L;
        }

        private List<K> getEquivalentRawKeys(K normalizedKey) {
            ArrayList<K> keys = rawKeysByNormalized.get(normalizedKey);
            if (keys == null || keys.isEmpty()) {
                return Collections.emptyList();
            }
            return new ArrayList<>(keys);
        }

        private void forEachNormalized(NormalizedEntryConsumer<K> consumer) {
            for (Map.Entry<K, Long> entry : normalizedAmounts.entrySet()) {
                long amount = entry.getValue();
                if (amount > 0L) {
                    consumer.accept(entry.getKey(), amount);
                }
            }
        }

        private void add(K rawKey, K normalizedKey, long amount) {
            if (rawKey == null || normalizedKey == null || amount <= 0L) {
                return;
            }

            ArrayList<K> keys = rawKeysByNormalized.computeIfAbsent(normalizedKey, ignored -> new ArrayList<>());
            if (!keys.contains(rawKey)) {
                keys.add(rawKey);
            }

            long normalizedAmount = AeStorageAmountMath.saturatedAdd(
                    getOrZero(normalizedAmounts, normalizedKey), amount);
            normalizedAmounts.put(normalizedKey, normalizedAmount);
        }

        private static <K> long getOrZero(Map<K, Long> values, K key) {
            Long amount = values.get(key);
            return amount != null ? amount.longValue() : 0L;
        }
    }
}
