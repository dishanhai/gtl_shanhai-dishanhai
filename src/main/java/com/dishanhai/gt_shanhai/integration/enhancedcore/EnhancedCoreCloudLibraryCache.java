package com.dishanhai.gt_shanhai.integration.enhancedcore;

import java.util.*;
import java.util.function.Function;

/** 山海：定義相同的母樣板共用逐 ID 編碼結果；驗證只編到首個有效 ID。 */
final class EnhancedCoreCloudLibraryCache<K, V> {
    static final class Expansion<V> {
        private final List<String> ids;
        private final Set<String> selected;
        private final Map<String, Optional<V>> encoded = new HashMap<>();
        private List<V> complete;

        private Expansion(List<String> ids) { this.ids = List.copyOf(ids); selected = Set.copyOf(ids); }

        V find(String id, Function<String, V> encoder) {
            if (!selected.contains(id)) return null;
            return encoded.computeIfAbsent(id, key -> Optional.ofNullable(encoder.apply(key))).orElse(null);
        }

        V first(Function<String, V> encoder) {
            for (String id : ids) { V child = find(id, encoder); if (child != null) return child; }
            return null;
        }

        List<V> all(Function<String, V> encoder) {
            if (complete == null) {
                var children = new ArrayList<V>();
                for (String id : ids) { V child = find(id, encoder); if (child != null) children.add(child); }
                complete = List.copyOf(children);
            }
            return complete;
        }
    }

    private final int maximumDefinitions;
    private final LinkedHashMap<K, Expansion<V>> definitions = new LinkedHashMap<>(8, .75f, true);
    private long revision = Long.MIN_VALUE;
    private boolean virtualProvider;

    EnhancedCoreCloudLibraryCache(int maximumDefinitions) {
        if (maximumDefinitions < 1) throw new IllegalArgumentException();
        this.maximumDefinitions = maximumDefinitions;
    }

    Expansion<V> get(K definition, long nextRevision, List<String> ids) {
        return get(definition, nextRevision, false, ids);
    }

    Expansion<V> get(K definition, long nextRevision, boolean nextVirtualProvider, List<String> ids) {
        if (revision != nextRevision || virtualProvider != nextVirtualProvider) {
            clear(); revision = nextRevision; virtualProvider = nextVirtualProvider;
        }
        var expansion = definitions.computeIfAbsent(definition, ignored -> new Expansion<>(ids));
        while (definitions.size() > maximumDefinitions) definitions.remove(definitions.keySet().iterator().next());
        return expansion;
    }

    void clear() { definitions.clear(); }

    static <V> List<V> publications(List<V> children, boolean validLease, String mode, Function<V, String> recipeMode) {
        return validLease ? children.stream().filter(child -> Objects.equals(mode, recipeMode.apply(child))).toList() : List.of();
    }
}
