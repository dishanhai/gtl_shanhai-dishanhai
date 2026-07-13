package com.dishanhai.gt_shanhai.common.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCatalogPublishSourceTest {

    @Test
    void shopConfigPublishesCompleteVersionedSnapshots() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/dishanhai/gt_shanhai/common/shop/ShopConfig.java"));

        assertAll(
                () -> assertTrue(source.contains("private static volatile ShopCatalogSnapshot snapshot")),
                () -> assertTrue(source.contains("public static ShopCatalogSnapshot snapshot()")),
                () -> assertTrue(source.contains("ShopCatalogSnapshot.build")),
                () -> assertTrue(source.contains("resolve(long revision, long entryKey)")),
                () -> assertTrue(source.contains("current.revision() != revision")),
                () -> assertTrue(source.contains("public static long keyOf(ShopEntry entry)")),
                () -> assertFalse(source.contains("private static final List<ShopEntry> ENTRIES")));
    }
}
