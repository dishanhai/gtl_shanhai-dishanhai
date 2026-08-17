package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JEIAESearchPrefixMixinSourceTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "JEIAESearchPrefixMixin.java");
    private static final Path BUILD = Path.of("build.gradle");

    @Test
    void usesTheJei15490PrefixAndConstructorContracts() throws IOException {
        String source = Files.readString(SOURCE);
        String build = Files.readString(BUILD);

        assertTrue(source.contains("import mezz.jei.common.search.PrefixInfo;"));
        assertTrue(source.contains("import mezz.jei.common.search.SearchMode;"));
        assertTrue(source.contains("import mezz.jei.api.search.ISearchStorageBuilderFactory;"));
        assertTrue(source.contains("ISearchStorageBuilderFactory searchStorageBuilderFactory, CallbackInfo ci"));
        assertTrue(source.contains("new PrefixInfo<>(\"gt_shanhai_item_id\", '*',"));
        assertTrue(source.contains("new PrefixInfo<>(\"gt_shanhai_tags\", '#',"));
        assertTrue(source.contains("searchStorageBuilderFactory));"));
        assertFalse(source.contains("mezz.jei.core.search"));
        assertFalse(source.contains("GeneralizedSuffixTree"));
        assertTrue(build.contains("jei-1.20.1-forge-15.49.0.188.jar"));
    }
}
