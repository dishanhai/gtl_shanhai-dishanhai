package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.AdvancedSearchUtil;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import com.dishanhai.gt_shanhai.api.TextFormatParser;
import net.minecraft.ChatFormatting;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 山海目录搜索：每个 revision 预建文本，查询缓存按分类隔离且有固定上限。 */
public final class ShopCatalogSearchIndex {
    public record Entry(long entryKey, String displayName, List<String> ids) {}
    public record Suggestion(long entryKey, String displayName, String completion) {}
    private record SuggestionText(String name, String completionName, String normalizedName, List<String> ids,
                                  List<String> normalizedIds) {}
    private record RankedSuggestion(int rank, Suggestion suggestion) {}
    private record QueryKey(String query, String scope) {}
    private static final int CACHE_LIMIT = 64;
    private final Map<Long, AdvancedSearchUtil.PreparedText> texts = new LinkedHashMap<>();
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
        texts.clear();
        suggestionTexts.clear();
        cache.clear();
        for (var stub : entries) {
            String name = plainName(stub.displayName());
            StringBuilder text = new StringBuilder(name);
            for (String id : stub.ids()) text.append(' ').append(id);
            texts.put(stub.entryKey(), AdvancedSearchUtil.prepareText(text.toString()));
            suggestionTexts.put(stub.entryKey(), new SuggestionText(stub.displayName(), name,
                    AdvancedSearchUtil.prepareText(name).text(), stub.ids(),
                    stub.ids().stream().map(id -> AdvancedSearchUtil.prepareText(id).text()).toList()));
        }
    }

    /** candidates=null 表示全店；分类列表由 ClientShopCatalog 已有分组索引提供。 */
    public List<Long> search(String query, String scope, List<Long> candidates) {
        var prepared = AdvancedSearchUtil.prepareQuery(query);
        QueryKey key = new QueryKey(prepared.text(), scope);
        List<Long> cached = cache.get(key);
        if (cached != null) return cached;
        List<Long> result = new ArrayList<>();
        for (Long entryKey : candidates == null ? texts.keySet() : candidates) {
            var text = texts.get(entryKey);
            if (text != null && text.matches(prepared)) result.add(entryKey);
        }
        List<Long> immutable = List.copyOf(result);
        cache.put(key, immutable);
        if (cache.size() > CACHE_LIMIT) cache.remove(cache.keySet().iterator().next());
        return immutable;
    }

    /** 山海：只从当前可见候选中联想；有界插入排序，不加载商品详情，也不排序整个目录。 */
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
        List<RankedSuggestion> best = new ArrayList<>();
        for (Long key : candidates == null ? texts.keySet() : candidates) {
            var text = texts.get(key);
            if (text == null || (matchText && !text.matches(prepared))) continue;
            var source = suggestionTexts.get(key);
            int rank = matchRank(source.normalizedName(), prepared.text());
            String completion = source.completionName();
            if (rank == 3) {
                int idRank = 3;
                for (int i = 0; i < source.ids().size(); i++) {
                    int next = matchRank(source.normalizedIds().get(i), prepared.text());
                    if (next < idRank) {
                        idRank = next;
                        completion = source.ids().get(i);
                    }
                }
                rank += idRank;
            }
            int at = 0;
            while (at < best.size() && best.get(at).rank() <= rank) at++;
            if (at >= limit) continue;
            best.add(at, new RankedSuggestion(rank, new Suggestion(key, source.name(), completion)));
            if (best.size() > limit) best.remove(best.size() - 1);
        }
        return best.stream().map(RankedSuggestion::suggestion).toList();
    }

    private static int matchRank(String text, String query) {
        if (text.equals(query)) return 0;
        if (text.startsWith(query)) return 1;
        return text.contains(query) ? 2 : 3;
    }

    private static String plainName(String name) {
        if (name.indexOf('&') >= 0) {
            var parsed = TextFormatParser.parseFormatting(name);
            if (parsed.flags.hasEffect() || parsed.flags.gradientTheme != null) name = parsed.cleanText;
        }
        return ChatFormatting.stripFormatting(name);
    }
}
