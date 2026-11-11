package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;
import com.dishanhai.gt_shanhai.common.shop.ShopResourceFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 山海：每份目录视图按相同范围构建分类、搜索和联想，避免管理视图与浏览视图互串缓存。 */
public final class ShopCatalogView {
    private final boolean editMode;
    private final List<ShopCatalogManifest.Stub> stubs;
    private final ShopCatalogSearchIndex searchIndex = new ShopCatalogSearchIndex(List.of());
    private final Map<String, List<Long>> groups = new LinkedHashMap<>();
    private final Map<String, List<String>> categories = new LinkedHashMap<>();

    ShopCatalogView(ShopCatalogManifest manifest, boolean editMode) {
        this.editMode = editMode;
        stubs = manifest.stubs().stream().filter(stub -> includes(stub, Set.of())).toList();
        searchIndex.rebuildEntries(stubs.stream()
                .map(stub -> new ShopCatalogSearchIndex.Entry(stub.entryKey(), stub.displayName(), stub.goodsIds()))
                .toList());
        Map<String, LinkedHashSet<String>> children = new LinkedHashMap<>();
        for (var stub : stubs) {
            addToGroup("", stub.entryKey());
            String parent = "";
            for (String category : List.of(stub.top(), stub.sub(), stub.sub2(), stub.sub3())) {
                if (category.isEmpty()) break;
                children.computeIfAbsent(parent, ignored -> new LinkedHashSet<>()).add(category);
                parent = parent.isEmpty() ? category : parent + "/" + category;
                addToGroup(parent, stub.entryKey());
            }
        }
        groups.replaceAll((path, keys) -> List.copyOf(keys));
        children.forEach((path, discovered) -> {
            var ordered = new LinkedHashSet<String>();
            for (String category : manifest.categoryOrder().getOrDefault(path, List.of())) {
                if (discovered.contains(category)) ordered.add(category);
            }
            ordered.addAll(discovered);
            categories.put(path, List.copyOf(ordered));
        });
    }

    private void addToGroup(String path, long key) {
        groups.computeIfAbsent(path, ignored -> new ArrayList<>()).add(key);
    }

    public List<String> topCategories() { return categories.getOrDefault("", List.of()); }
    public List<String> subCategories(String top) { return children(top); }
    public List<String> subCategories2(String top, String sub) { return children(top, sub); }
    public List<String> subCategories3(String top, String sub, String sub2) { return children(top, sub, sub2); }

    private List<String> children(String... parts) {
        for (String part : parts) if (part == null || part.isEmpty()) return List.of();
        return categories.getOrDefault(String.join("/", parts), List.of());
    }

    public List<Long> keysOfGroup(String top, String sub, String sub2, String sub3) {
        return groups.getOrDefault(groupPath(top, sub, sub2, sub3), List.of());
    }

    public List<Long> searchKeys(String query, String top, String sub, String sub2, String sub3) {
        String path = groupPath(top, sub, sub2, sub3);
        return searchIndex.search(query, path, groups.getOrDefault(path, List.of()));
    }

    public List<ShopCatalogSearchIndex.Suggestion> searchSuggestions(String query, List<Long> candidates, int limit) {
        return searchIndex.suggest(query, candidates, limit);
    }

    public List<Long> resourceKeys(List<ShopResourceFilter.Resource> resources, boolean costs) {
        return ShopResourceFilter.keys(stubs, resources, costs, true);
    }

    public boolean includes(ShopCatalogManifest.Stub stub, Set<String> personalIds) {
        return stub != null && (editMode || (!stub.hidden() && !personalIds.contains(stub.stableId())));
    }

    /** 公共隐藏始终优先；即使曾被玩家隐藏，也不能出现在个人抽屉。 */
    public List<ShopCatalogManifest.Stub> personalHiddenEntries(Set<String> personalIds) {
        if (personalIds.isEmpty()) return List.of();
        return stubs.stream().filter(stub -> !stub.hidden() && personalIds.contains(stub.stableId())).toList();
    }

    private static String groupPath(String... parts) {
        int count = parts.length;
        while (count > 0 && (parts[count - 1] == null || parts[count - 1].isEmpty())) count--;
        return String.join("/", java.util.Arrays.copyOf(parts, count));
    }
}
