package com.dishanhai.gt_shanhai.common.machine.part;

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

    synchronized void recordChange(K rawKey, long delta) {
        if (snapshot != null) {
            snapshot.recordChange(rawKey, normalizer.apply(rawKey), delta);
        }
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
        private final Map<K, Long> rawAmounts = new LinkedHashMap<>();
        private final Map<K, ArrayList<K>> rawKeysByNormalized = new LinkedHashMap<>();

        private static <K> Snapshot<K> from(Iterable<Entry<K>> entries, Function<K, K> normalizer) {
            Snapshot<K> snapshot = new Snapshot<>();
            for (Entry<K> entry : entries) {
                if (entry == null || entry.key() == null || entry.amount() <= 0L) {
                    continue;
                }
                snapshot.recordChange(entry.key(), normalizer.apply(entry.key()), entry.amount());
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

        private void recordChange(K rawKey, K normalizedKey, long delta) {
            if (rawKey == null || normalizedKey == null || delta == 0L) {
                return;
            }

            long oldRaw = getOrZero(rawAmounts, rawKey);
            long newRaw = oldRaw + delta;
            if (newRaw < 0L) {
                newRaw = 0L;
            }
            long actualDelta = newRaw - oldRaw;
            if (actualDelta == 0L) {
                return;
            }

            if (newRaw > 0L) {
                rawAmounts.put(rawKey, newRaw);
                ArrayList<K> keys = rawKeysByNormalized.get(normalizedKey);
                if (keys == null) {
                    keys = new ArrayList<>();
                    rawKeysByNormalized.put(normalizedKey, keys);
                }
                if (!keys.contains(rawKey)) {
                    keys.add(rawKey);
                }
            } else {
                rawAmounts.remove(rawKey);
                ArrayList<K> keys = rawKeysByNormalized.get(normalizedKey);
                if (keys != null) {
                    keys.remove(rawKey);
                    if (keys.isEmpty()) {
                        rawKeysByNormalized.remove(normalizedKey);
                    }
                }
            }

            long newNormalized = getOrZero(normalizedAmounts, normalizedKey) + actualDelta;
            if (newNormalized > 0L) {
                normalizedAmounts.put(normalizedKey, newNormalized);
            } else {
                normalizedAmounts.remove(normalizedKey);
            }
        }

        private static <K> long getOrZero(Map<K, Long> values, K key) {
            Long amount = values.get(key);
            return amount != null ? amount.longValue() : 0L;
        }
    }
}
