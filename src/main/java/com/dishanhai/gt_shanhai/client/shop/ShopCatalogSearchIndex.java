package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.AdvancedSearchUtil;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 山海目录搜索：每个 revision 预建文本，查询缓存按分类隔离且有固定上限。 */
public final class ShopCatalogSearchIndex {
    private record QueryKey(String query, String scope) {}
    private static final int CACHE_LIMIT = 64;
    private final Map<Long, AdvancedSearchUtil.PreparedText> texts = new LinkedHashMap<>();
    private final Map<QueryKey, List<Long>> cache = new LinkedHashMap<>(16, 0.75f, true);

    public ShopCatalogSearchIndex(List<ShopCatalogManifest.Stub> stubs) {
        rebuild(stubs);
    }

    public void rebuild(List<ShopCatalogManifest.Stub> stubs) {
        texts.clear();
        cache.clear();
        for (var stub : stubs) {
            if (stub.hidden()) continue;
            StringBuilder text = new StringBuilder(stub.displayName());
            for (String id : stub.goodsIds()) text.append(' ').append(id);
            texts.put(stub.entryKey(), AdvancedSearchUtil.prepareText(text.toString()));
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
}
