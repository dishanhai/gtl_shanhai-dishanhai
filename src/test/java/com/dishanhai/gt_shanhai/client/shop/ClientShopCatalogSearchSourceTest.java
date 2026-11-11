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
        assertTrue(index.contains("AdvancedSearchUtil.prepareText(name)"), "文本必须保留现有标点规范化规则");
        assertTrue(index.contains("AdvancedSearchUtil.prepareQuery(query)"), "每次查询只分词一次");
        assertTrue(index.contains("com.dishanhai.sh_better_ui.client.gui.search.SearchIndex"),
                "目录应复用 SHBUI 公开索引的内建拼音匹配");
        assertTrue(index.contains("index.suggestMatches("), "已由宿主匹配的前缀候选只能复用排名");
        assertFalse(index.contains(".matches(prepared)"), "目录不能再依赖 JECharacters 桥接完成拼音匹配");
        assertFalse(source.contains("haystack.toString().toLowerCase(Locale.ROOT).contains(normalized)"),
                "不能退回纯 contains，否则拼音查询会把中文商品全部过滤掉");
    }
}
