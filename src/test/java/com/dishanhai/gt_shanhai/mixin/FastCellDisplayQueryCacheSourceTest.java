package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastCellDisplayQueryCacheSourceTest {

    private static final Path MIXIN_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "FastCellDisplayQueryCacheMixin.java");
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");
    private static final Path CACHE_ENTRY_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "ae2", "FastCellDisplayQueryCacheEntry.java");

    @Test
    void preciseDisplayQueryCacheIsBoundedByNetworkRevisionAndVisibleKeys() throws IOException {
        String source = Files.readString(MIXIN_SOURCE);
        String config = Files.readString(MIXIN_CONFIG);
        String cacheEntry = Files.readString(CACHE_ENTRY_SOURCE);

        assertTrue(source.contains("FastCellDisplayPackets"));
        assertTrue(source.contains("PreciseInventoryDisplayService;query"));
        assertTrue(source.contains("IStorageServiceRevisionAccess"));
        assertTrue(source.contains("gtShanhai$captureMenu"));
        assertTrue(source.contains("gtShanhai$CURRENT_MENU.remove()"));
        assertTrue(source.contains("gtShanhai$releaseMenu"));
        assertTrue(source.contains("ThreadLocal<MEStorageMenu>"));
        assertTrue(source.contains("cache.revision == revision"));
        assertTrue(source.contains("cache.storage == storage"));
        assertTrue(source.contains("cache.topology == topology"));
        assertTrue(source.contains("gtlcore$getTopologyVersion"));
        assertTrue(source.contains("cache.keys.equals(keys)"));
        assertTrue(source.contains("List.copyOf(keys)"));
        assertTrue(source.contains("Long.MIN_VALUE"), "无法取得 revision 时必须回退原始精确查询");
        assertTrue(source.contains("storage != service.getInventory()"),
                "只有网络 inventory 才能使用 StorageService revision 快取");
        assertTrue(source.contains("FastCellDisplayQueryCacheEntry"),
                "快取資料必須使用 Mixin package 外的普通類別");
        assertFalse(source.contains("class QueryCache"),
                "不可在 Mixin package 重新加入會被目標類直接引用的內部快取類");
        assertTrue(cacheEntry.contains("package com.dishanhai.gt_shanhai.api.ae2;"),
                "快取資料類別不可放在 Mixin package，避免 Mixin 類別載入保護");
        assertTrue(config.contains("\"FastCellDisplayQueryCacheMixin\""));
    }
}
