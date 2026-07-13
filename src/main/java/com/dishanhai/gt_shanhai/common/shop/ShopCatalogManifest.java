package com.dishanhai.gt_shanhai.common.shop;

import java.util.List;

/** 供客户端先建立分类、滚动高度和轻量搜索索引的山海商店目录清单。 */
public final class ShopCatalogManifest {

    public record Stub(long entryKey, String top, String sub, boolean hidden,
                       int chunkId, String linkKey, String displayName, List<String> goodsIds) {
        public Stub {
            top = top == null ? "" : top;
            sub = sub == null ? "" : sub;
            linkKey = linkKey == null ? "" : linkKey;
            displayName = displayName == null ? "" : displayName;
            goodsIds = goodsIds == null ? List.of() : List.copyOf(goodsIds);
        }
    }

    private final long revision;
    private final boolean ready;
    private final List<Stub> stubs;

    public ShopCatalogManifest(long revision, boolean ready, List<Stub> stubs) {
        this.revision = revision;
        this.ready = ready;
        this.stubs = stubs == null ? List.of() : List.copyOf(stubs);
    }

    public static ShopCatalogManifest empty() {
        return new ShopCatalogManifest(0L, false, List.of());
    }

    public long revision() { return revision; }
    public boolean ready() { return ready; }
    public List<Stub> stubs() { return stubs; }
}
