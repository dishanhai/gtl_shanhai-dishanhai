package com.dishanhai.gt_shanhai.client.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientShopCatalogSearchSourceTest {

    @Test
    void catalogSearchUsesPinyinAwareMatcher() throws Exception {
        String source = Files.readString(Path.of("src", "main", "java", "com", "dishanhai",
                "gt_shanhai", "client", "shop", "ClientShopCatalog.java"));

        String index = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/shop/ShopCatalogSearchIndex.java"));
        String matcher = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/client/gui/scaled/AdvancedSearchUtil.java"));
        assertTrue(source.contains("searchIndex.rebuild(manifest.stubs())"), "目录更新必须重建搜索索引");
        assertTrue(index.contains("AdvancedSearchUtil.prepareText(text.toString())"), "文本必须使用现有规范化规则");
        assertTrue(index.contains("AdvancedSearchUtil.prepareQuery(query)"), "每次查询只分词一次");
        assertTrue(matcher.contains("PinyinSearchBridge.contains(text, token)"), "预建索引不能丢失拼音匹配");
        assertFalse(source.contains("haystack.toString().toLowerCase(Locale.ROOT).contains(normalized)"),
                "不能退回纯 contains，否则拼音查询会把中文商品全部过滤掉");
    }
}
