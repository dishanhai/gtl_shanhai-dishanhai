package com.dishanhai.gt_shanhai.mixin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreciseInventoryDisplayQueryPerformanceSourceTest {

    private static final Path MIXIN_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "PreciseInventoryDisplayQueryMixin.java");
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");

    @Test
    void queryMixinKeepsTheGtlcoreTraversalContractWhileRemovingStreamAllocation() throws IOException {
        String source = Files.readString(MIXIN_SOURCE);
        String config = Files.readString(MIXIN_CONFIG);

        assertTrue(source.contains("PreciseInventoryDisplayService$Query"));
        assertTrue(source.contains("private void visit(MEStorage storage, List<AEKey> requested, int depth)"));
        assertTrue(source.contains("this.remaining"));
        assertTrue(source.contains("depth <= 64"));
        assertTrue(source.contains("PreciseStorageAmount"));
        assertTrue(source.contains("ThroughputStorageView"));
        assertTrue(source.contains("gtlcore$canDisplay"));
        assertFalse(source.contains(".stream()"), "精确库存递归热路径不得重新引入 Stream pipeline");
        assertFalse(source.contains("computeIfAbsent"), "visited/otherStorages 不得每次通过 lambda 查找");
        assertTrue(config.contains("\"PreciseInventoryDisplayQueryMixin\""),
                "精确库存查询性能 Mixin 必须注册到服务端 mixin 配置");
    }
}
