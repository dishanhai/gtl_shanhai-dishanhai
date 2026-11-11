package com.dishanhai.gt_shanhai.common.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** 山海商店资源筛选：只读完整目录元数据，不触发商品分块加载。 */
public final class ShopResourceFilter {
    private ShopResourceFilter() {}

    /** identity 为空表示按类型与 ID 匹配；物品可携带 ShopGoodsIdentity 生成的稳定身份。 */
    public record Resource(boolean fluid, String id, String identity) {
        public Resource {
            id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            identity = fluid || identity == null ? "" : identity;
        }
    }

    public static boolean matches(List<Resource> selected, List<Resource> candidates) {
        return compile(selected).test(candidates);
    }

    private static Predicate<List<Resource>> compile(List<Resource> selected) {
        if (selected.isEmpty()) return candidates -> true;
        Set<Resource> exact = new HashSet<>();
        Set<Resource> wildcards = new HashSet<>();
        Set<Resource> ids = new HashSet<>();
        for (Resource wanted : selected) {
            if (wanted.id().isEmpty()) continue;
            Resource id = new Resource(wanted.fluid(), wanted.id(), "");
            ids.add(id);
            if (wanted.identity().isEmpty()) wildcards.add(id);
            else exact.add(wanted);
        }
        return candidates -> {
            for (Resource candidate : candidates) {
                Resource id = new Resource(candidate.fluid(), candidate.id(), "");
                if (wildcards.contains(id) || exact.contains(candidate)
                        || (candidate.identity().isEmpty() && ids.contains(id))) return true;
            }
            return false;
        };
    }

    public static List<Long> keys(List<ShopCatalogManifest.Stub> stubs, List<Resource> selected, boolean costs) {
        return keys(stubs, selected, costs, false);
    }

    public static List<Long> keys(List<ShopCatalogManifest.Stub> stubs, List<Resource> selected, boolean costs,
                                  boolean includeHidden) {
        List<Long> result = new ArrayList<>();
        var matcher = compile(selected);
        for (var stub : stubs) {
            if ((includeHidden || !stub.hidden()) && matcher.test(costs ? stub.costResources() : stub.goodsResources())) {
                result.add(stub.entryKey());
            }
        }
        return List.copyOf(result);
    }
}
