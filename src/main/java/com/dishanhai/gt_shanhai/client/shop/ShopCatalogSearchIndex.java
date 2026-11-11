package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.AdvancedSearchUtil;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import com.dishanhai.gt_shanhai.api.TextFormatParser;
import com.dishanhai.sh_better_ui.client.gui.search.SearchIndex;
import net.minecraft.ChatFormatting;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;

/** 山海目录搜索：每个 revision 预建文本，查询缓存按分类隔离且有固定上限。 */
public final class ShopCatalogSearchIndex {
    public record Entry(long entryKey, String displayName, List<String> ids) {}
    public record Suggestion(long entryKey, String displayName, String completion) {}
    private record SuggestionText(String name, String completionName, List<String> ids) {}
    private record QueryKey(String query, String scope) {}
    private static final int CACHE_LIMIT = 64;
    private SearchIndex<Long> index = new SearchIndex<>(List.of());
    private final Map<Long, SuggestionText> suggestionTexts = new LinkedHashMap<>();
    private final Map<QueryKey, List<Long>> cache = new LinkedHashMap<>(16, 0.75f, true);

    public ShopCatalogSearchIndex(List<ShopCatalogManifest.Stub> stubs) {
        rebuild(stubs);
    }

    public void rebuild(List<ShopCatalogManifest.Stub> stubs) {
        rebuildEntries(stubs.stream().filter(stub -> !stub.hidden())
                .map(stub -> new Entry(stub.entryKey(), stub.displayName(), stub.goodsIds())).toList());
    }

    /** 山海：选择器可复用同一索引，不构造虚假的商店 manifest。 */
    public void rebuildEntries(List<Entry> entries) {
        suggestionTexts.clear();
        cache.clear();
        List<SearchIndex.Entry<Long>> searchable = new ArrayList<>();
        var displayOnlyNames = new HashSet<Long>();
        for (var stub : entries) {
            String name = plainName(stub.displayName());
            String normalizedName = AdvancedSearchUtil.prepareText(name).text();
            List<String> ids = stub.ids().stream().map(id -> AdvancedSearchUtil.prepareText(id).text()).toList();
            String fallbackId = "";
            for (int i = 0; i < ids.size(); i++) {
                if (!ids.get(i).isEmpty()) {
                    fallbackId = stub.ids().get(i);
                    break;
                }
            }
            suggestionTexts.put(stub.entryKey(), new SuggestionText(name.isBlank() ? fallbackId : stub.displayName(),
                    normalizedName.isEmpty() ? fallbackId : name, List.copyOf(stub.ids())));
            String searchableName = normalizedName.isEmpty()
                    ? ids.stream().filter(id -> !id.isEmpty()).findFirst().orElse("") : normalizedName;
            if (!searchableName.isEmpty()) {
                searchable.add(new SearchIndex.Entry<>(stub.entryKey(), searchableName, ids, List.of()));
                if (normalizedName.isEmpty()) displayOnlyNames.add(stub.entryKey());
            }
        }
        index = new SearchIndex<>(searchable, entry -> displayOnlyNames.contains(entry.value()));
    }

    /** candidates=null 表示全店；分类列表由 ClientShopCatalog 已有分组索引提供。 */
    public List<Long> search(String query, String scope, List<Long> candidates) {
        var prepared = AdvancedSearchUtil.prepareQuery(query);
        QueryKey key = new QueryKey(prepared.text(), scope);
        List<Long> cached = cache.get(key);
        if (cached != null) return cached;
        var matches = prepared.tokens().isEmpty() ? suggestionTexts.keySet()
                : new HashSet<>(index.searchPlainText(prepared.text()).stream().map(SearchIndex.Entry::value).toList());
        List<Long> result = (candidates == null ? suggestionTexts.keySet().stream() : candidates.stream())
                .filter(matches::contains).toList();
        List<Long> immutable = List.copyOf(result);
        cache.put(key, immutable);
        if (cache.size() > CACHE_LIMIT) cache.remove(cache.keySet().iterator().next());
        return immutable;
    }

    /** 山海：只从当前可见候选中联想；按原有七档优先级稳定分桶，不加载商品详情。 */
    public List<Suggestion> suggest(String query, List<Long> candidates, int limit) {
        return suggest(query, candidates, limit, true);
    }

    /** 前缀搜索已经由调用方按标签／提示等语义匹配，只复用排序，不再用名称二次排除。 */
    public List<Suggestion> suggestMatches(String query, List<Long> matches, int limit) {
        return suggest(query, matches, limit, false);
    }

    private List<Suggestion> suggest(String query, List<Long> candidates, int limit, boolean matchText) {
        var prepared = AdvancedSearchUtil.prepareQuery(query);
        if (limit <= 0 || prepared.tokens().isEmpty()) return List.of();
        List<Long> ordered = candidates == null ? List.copyOf(suggestionTexts.keySet()) : candidates;
        List<SearchIndex.Suggestion<Long>> matches = matchText ? index.suggestPlainText(prepared.text(), ordered, limit)
                : index.suggestMatches(prepared.text(), ordered, limit);
        return matches.stream().map(suggestion -> {
            var entry = suggestion.entry();
            var source = suggestionTexts.get(entry.value());
            int id = suggestion.completion().equals(entry.name()) ? -1
                    : entry.ids().indexOf(suggestion.completion());
            String completion = id < 0 ? source.completionName() : source.ids().get(id);
            return new Suggestion(entry.value(), source.name(), completion);
        }).toList();
    }

    private static String plainName(String name) {
        if (name.indexOf('&') >= 0) {
            var parsed = TextFormatParser.parseFormatting(name);
            if (parsed.flags.hasEffect() || parsed.flags.gradientTheme != null) name = parsed.cleanText;
        }
        return ChatFormatting.stripFormatting(name);
    }
}
