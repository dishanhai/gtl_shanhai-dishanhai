package com.dishanhai.gt_shanhai.api.ae2;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AeStoragePerformanceSourceTest {

    private static final Path AE_STORAGE_MATH = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "ae2", "AeStorageAmountMath.java");
    private static final Path EQUIVALENT_CACHE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "part", "EquivalentKeySnapshotCache.java");

    @Test
    void saturatedMergeUsesEmptyTargetFastPathAndEquivalentCacheUsesSetDeduping() throws IOException {
        String math = Files.readString(AE_STORAGE_MATH);
        String cache = Files.readString(EQUIVALENT_CACHE);

        assertTrue(math.contains("target.addAll(source)"),
                "空目标合并应直接整份拷贝，避免逐条 get/set 饱和叠加");
        assertFalse(math.contains("if (target instanceof KeyCounterListsAccessor targetAccessor"),
                "若没有空目标快拷贝，KeyCounter 合并会持续走慢路径");

        assertTrue(math.contains(".addTo(key, amount)"),
                "饱和累加必须经底层 records.addTo 单查找读改写，不得每 key 先 get 再 set 双查找");
        assertTrue(math.contains("unreflectGetter"),
                "包私有 VariantCounter/records 只能走类级缓存 MethodHandle 直达（LRN-20260726-004）");
        assertTrue(math.contains("if (addSaturatedDirect(target, key, amount))"),
                "直达通道必须保留公开 API 回退路径：反射被拒或未知 VariantCounter 子类时语义不变");

        assertTrue(cache.contains("LinkedHashSet<K>"),
                "等价键快照应使用可去重的插入顺序集合，而不是线性 contains");
        assertFalse(cache.contains("keys.contains(rawKey)"),
                "等价键快照不得在每个条目上做线性去重扫描");
    }
}
