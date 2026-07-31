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

        assertTrue(source.contains("import com.dishanhai.gt_shanhai.client.gui.scaled.AdvancedSearchUtil;"),
                "商店目录搜索必须复用拼音感知搜索工具");
        assertTrue(source.contains("AdvancedSearchUtil.match(searchText.toString(), normalized)"),
                "商品显示名 + ID 的匹配必须走 AdvancedSearchUtil，才能继承 JECharacters 拼音搜索");
        assertFalse(source.contains("haystack.toString().toLowerCase(Locale.ROOT).contains(normalized)"),
                "不能退回纯 contains，否则拼音查询会把中文商品全部过滤掉");
    }
}
