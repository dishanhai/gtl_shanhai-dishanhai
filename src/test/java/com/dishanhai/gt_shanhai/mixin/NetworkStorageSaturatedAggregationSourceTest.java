package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkStorageSaturatedAggregationSourceTest {

    private static final Path CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");
    private static final Path MIXIN = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "NetworkStorageSaturatedAggregationMixin.java");

    @Test
    void networkStorageUsesSaturatedPerProviderMerge() throws IOException {
        String source = Files.readString(MIXIN);
        assertTrue(Files.readString(CONFIG).contains("NetworkStorageSaturatedAggregationMixin"));
        assertTrue(source.contains("MEStorage;getAvailableStacks"));
        assertTrue(source.contains("getAvailableStacksSaturated"));
        assertTrue(source.contains("providerContribution"));
    }
}
