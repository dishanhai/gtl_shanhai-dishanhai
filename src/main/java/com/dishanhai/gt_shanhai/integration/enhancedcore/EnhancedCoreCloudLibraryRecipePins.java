package com.dishanhai.gt_shanhai.integration.enhancedcore;

import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
import java.util.HashMap;
import java.util.Map;

/** 山海：相同 IO 的 probe record 也不能共用 ID；弱引用避免掃描後永久留存。 */
final class EnhancedCoreCloudLibraryRecipePins<T> {
    private static final class IdentityReference<T> extends WeakReference<T> {
        private final int hash;
        IdentityReference(T value, ReferenceQueue<T> queue) { super(value, queue); hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof IdentityReference<?> reference
                    && get() != null && get() == reference.get();
        }
    }
    private final ReferenceQueue<T> released = new ReferenceQueue<>();
    private final Map<IdentityReference<T>, String> pins = new HashMap<>();

    private void drain() {
        java.lang.ref.Reference<? extends T> reference;
        while ((reference = released.poll()) != null) pins.remove(reference);
    }

    synchronized void put(T probe, String recipe) {
        drain(); pins.put(new IdentityReference<>(probe, released), recipe);
    }

    synchronized String get(T probe) {
        drain(); return pins.get(new IdentityReference<>(probe, null));
    }

    synchronized void clear() { pins.clear(); }
}
