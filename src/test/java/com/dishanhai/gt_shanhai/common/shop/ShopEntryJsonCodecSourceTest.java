package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopEntryJsonCodecSourceTest {

    @Test
    void codecOwnsEveryExistingShopEntryField() throws Exception {
        Path path = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/shop/ShopEntryJsonCodec.java");
        assertTrue(Files.exists(path), "单条商品 JSON codec 尚未创建");

        String source = Files.readString(path);
        assertAll(
                () -> assertTrue(source.contains("periodTicks")),
                () -> assertTrue(source.contains("periodLimit")),
                () -> assertTrue(source.contains("tradeMode")),
                () -> assertTrue(source.contains("rewardPool")),
                () -> assertTrue(source.contains("goodsList")),
                () -> assertTrue(source.contains("displayName")),
                () -> assertTrue(source.contains("linkKey")),
                () -> assertTrue(source.contains("linkTo")),
                () -> assertTrue(source.contains("ftbqTableId")),
                () -> assertTrue(source.contains("ftbqSubMode")));
    }
}
