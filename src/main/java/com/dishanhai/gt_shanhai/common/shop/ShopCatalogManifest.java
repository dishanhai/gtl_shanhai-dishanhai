package com.dishanhai.gt_shanhai.common.shop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 供客户端先建立分类、滚动高度和轻量搜索索引的山海商店目录清单。 */
public final class ShopCatalogManifest {

    /**
     * top/sub/sub2/sub3：分类路径按 "/" 最多拆 4 级（第 4 级吸收更深的剩余部分），见 ShopCatalogSnapshot#splitCategoryPath。
     *
     * <p>{@code prereqQuestId} 是该商品配置的前置 FTBQ 任务（十六进制 ID，空=没配）。放在 stub 而不是
     * 只留在完整 {@link ShopEntry} 里，是因为任务书那侧要反查「这个任务对应哪些商品」——完整条目按
     * chunk 惰性加载，没逛过的分类查不到；stub 是全量的，反查才不漏。</p>
     */
    public record Stub(long entryKey, String top, String sub, String sub2, String sub3, boolean hidden,
                       int chunkId, String linkKey, String displayName, List<String> goodsIds, String stableId,
                       String prereqQuestId) {
        public Stub {
            top = top == null ? "" : top;
            sub = sub == null ? "" : sub;
            sub2 = sub2 == null ? "" : sub2;
            sub3 = sub3 == null ? "" : sub3;
            linkKey = linkKey == null ? "" : linkKey;
            displayName = displayName == null ? "" : displayName;
            goodsIds = goodsIds == null ? List.of() : List.copyOf(goodsIds);
            stableId = stableId == null ? "" : stableId;
            prereqQuestId = prereqQuestId == null ? "" : prereqQuestId;
        }
    }

    private final long revision;
    private final boolean ready;
    private final List<Stub> stubs;
    /**
     * 分类页签显式排序：key=父路径（"/"拼接，""=顶级页签自身），value=该层已知分类的排序结果。
     * 未出现在这里的分类按发现顺序追加在末尾（见 ClientShopCatalog#applyOrder）；由
     * {@link ShopConfig} 持久化到 shop_category_order.json，拖拽排序页签后写回并重新广播。
     */
    private final Map<String, List<String>> categoryOrder;

    public ShopCatalogManifest(long revision, boolean ready, List<Stub> stubs, Map<String, List<String>> categoryOrder) {
        this.revision = revision;
        this.ready = ready;
        this.stubs = stubs == null ? List.of() : List.copyOf(stubs);
        Map<String, List<String>> orderCopy = new LinkedHashMap<>();
        if (categoryOrder != null) {
            for (Map.Entry<String, List<String>> e : categoryOrder.entrySet()) {
                orderCopy.put(e.getKey() == null ? "" : e.getKey(),
                        e.getValue() == null ? List.of() : List.copyOf(e.getValue()));
            }
        }
        this.categoryOrder = Map.copyOf(orderCopy);
    }

    public static ShopCatalogManifest empty() {
        return new ShopCatalogManifest(0L, false, List.of(), Map.of());
    }

    public long revision() { return revision; }
    public boolean ready() { return ready; }
    public List<Stub> stubs() { return stubs; }
    public Map<String, List<String>> categoryOrder() { return categoryOrder; }
}
