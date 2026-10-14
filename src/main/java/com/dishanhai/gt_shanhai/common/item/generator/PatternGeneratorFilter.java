package com.dishanhai.gt_shanhai.common.item.generator;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 配方预览和生成队列共用的纯判断，不依赖注册表。 */
public final class PatternGeneratorFilter {
    public static final int ANY_CIRCUIT = -1;
    public static final int ANY_TIER = -1;
    public static final int SORT_ID = 0;
    public static final int SORT_EUT_ASC = 1;
    public static final int SORT_EUT_DESC = 2;
    public static final int SORT_DURATION = 3;

    public record Material(String id, boolean fluid, int registryIndex) {}

    public record NameHits(BitSet items, BitSet fluids) {
        public NameHits {
            items = items == null ? new BitSet() : items;
            fluids = fluids == null ? new BitSet() : fluids;
        }

        public static NameHits empty() {
            return new NameHits(new BitSet(), new BitSet());
        }
    }

    private PatternGeneratorFilter() {}

    public static List<String> keywords(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.toLowerCase(Locale.ROOT).replace("\"", "")
                        .replace("\u201c", "").replace("\u201d", "").strip().split("\\s+"))
                .filter(token -> !token.isBlank()).distinct().toList();
    }

    public static boolean matchesCircuit(int selection, int actual) {
        return selection == ANY_CIRCUIT || selection == actual;
    }

    public static boolean matchesTier(int minimum, int maximum, int actual) {
        if (minimum >= 0 && maximum >= 0 && minimum > maximum) return false;
        if (minimum >= 0 && actual < minimum) return false;
        return maximum < 0 || actual <= maximum;
    }

    public static boolean matchesId(String query, String id) {
        List<String> words = keywords(query);
        return words.isEmpty() || containsKeyword(words, id == null ? "" : id);
    }

    public static int sortMode(int value) {
        if (value < SORT_ID) return SORT_ID;
        return Math.min(SORT_DURATION, value);
    }

    public static boolean containsKeyword(List<String> keywords, String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(normalized::contains);
    }

    public static boolean allows(List<Material> materials, List<String> words,
                                 BitSet namedItems, BitSet namedFluids, Set<String> excluded) {
        if (materials.stream().anyMatch(material -> excluded.contains(key(material.id(), material.fluid())))) return false;
        return words.isEmpty() || materials.stream().anyMatch(material ->
                containsKeyword(words, material.id()) || (material.registryIndex() >= 0 &&
                        (material.fluid() ? namedFluids : namedItems).get(material.registryIndex())));
    }

    public static String key(String id, boolean fluid) {
        return (fluid ? "fluid:" : "item:") + id;
    }
}
