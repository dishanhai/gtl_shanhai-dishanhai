package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.shop.ShopCatalogSearchIndex;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** 山海：多选器已有来源／模组／前缀匹配结果到共用联想索引的适配。 */
public final class PickerSearchSuggestions {
    private final ShopCatalogSearchIndex index = new ShopCatalogSearchIndex(List.of());
    private final Map<Long, String> ids = new LinkedHashMap<>();

    public PickerSearchSuggestions(List<PickerSortEntry> entries) { rebuild(entries); }

    public void rebuild(List<PickerSortEntry> entries) {
        ids.clear();
        index.rebuildEntries(entries.stream().map(entry -> {
            ids.put((long) entry.getIndex(), entry.getId());
            return new ShopCatalogSearchIndex.Entry(entry.getIndex(), entry.getName(), List.of(entry.getId()));
        }).toList());
    }

    public List<ShopCatalogSearchIndex.Suggestion> suggest(String query, List<Integer> matches) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() || matches.isEmpty()) return List.of();
        List<Long> candidates = matches.stream().map(Integer::longValue).toList();
        if ("@#$*".indexOf(q.charAt(0)) < 0) return index.suggest(q, candidates, 5);
        String ranking = q.substring(1).trim();
        if (ranking.isEmpty()) return List.of();
        return index.suggestMatches(ranking, candidates, 5).stream()
                .map(suggestion -> new ShopCatalogSearchIndex.Suggestion(suggestion.entryKey(),
                        suggestion.displayName(), "*" + ids.get(suggestion.entryKey()))).toList();
    }
}
