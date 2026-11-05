package com.dishanhai.gt_shanhai.client.loading;

import com.google.common.collect.ForwardingMap;
import com.google.common.collect.ForwardingSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** 山海：沿用原集合與處理順序，在一筆處理返回後回報；不複製整份配方。 */
public final class ShanhaiLoadingProgress {
    private ShanhaiLoadingProgress() {}

    public static <T> Iterable<T> track(Iterable<T> source, Consumer<? super T> processed) {
        return new Iterable<>() {
            @Override public Iterator<T> iterator() { return trackIterator(source.iterator(), processed); }
            @Override public void forEach(Consumer<? super T> action) {
                source.forEach(value -> { action.accept(value); processed.accept(value); });
            }
        };
    }

    public static <K, V> Map<K, V> trackMap(Map<K, V> source, Consumer<? super Map.Entry<K, V>> processed) {
        return new ForwardingMap<>() {
            @Override protected Map<K, V> delegate() { return source; }
            @Override public Set<Map.Entry<K, V>> entrySet() {
                return new ForwardingSet<>() {
                    @Override protected Set<Map.Entry<K, V>> delegate() { return source.entrySet(); }
                    @Override public Iterator<Map.Entry<K, V>> iterator() {
                        return trackIterator(source.entrySet().iterator(), processed);
                    }
                };
            }
        };
    }

    private static <T> Iterator<T> trackIterator(Iterator<T> source, Consumer<? super T> processed) {
        return new Iterator<>() {
            private T previous;
            private boolean pending;
            private void reportPrevious() {
                if (pending) { pending = false; processed.accept(previous); }
            }
            @Override public boolean hasNext() { reportPrevious(); return source.hasNext(); }
            @Override public T next() {
                reportPrevious();
                previous = source.next();
                pending = true;
                return previous;
            }
            @Override public void remove() { source.remove(); }
            @Override public void forEachRemaining(Consumer<? super T> action) {
                reportPrevious();
                source.forEachRemaining(value -> { action.accept(value); processed.accept(value); });
            }
        };
    }
}
