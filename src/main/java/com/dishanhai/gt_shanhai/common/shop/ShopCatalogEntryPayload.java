package com.dishanhai.gt_shanhai.common.shop;

import java.nio.charset.StandardCharsets;

/** 网络线程可安全搬运的纯 JSON 商品负载；实体化必须回到 Minecraft 主线程。 */
public record ShopCatalogEntryPayload(long entryKey, String json) {
    public ShopCatalogEntryPayload {
        json = json == null ? "" : json;
    }

    public int estimatedUtf8Bytes() {
        return json.getBytes(StandardCharsets.UTF_8).length + Long.BYTES + Integer.BYTES;
    }
}
