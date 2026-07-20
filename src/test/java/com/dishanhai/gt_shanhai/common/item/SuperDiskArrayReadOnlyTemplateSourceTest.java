package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuperDiskArrayReadOnlyTemplateSourceTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "SuperDiskArrayInventory.java");

    @Test
    void uuidLessNullProviderAccessUsesSideEffectFreeTemplateLoad() throws IOException {
        String source = Files.readString(SOURCE).replace("\r\n", "\n");

        assertTrue(source.contains("boolean readOnlyTemplate = saveProvider == null && (tag == null || !tag.hasUUID(TAG_UUID));"),
                "无 UUID 且无保存器的访问必须被识别为 JEI/探测用只读模板");
        assertTrue(source.contains("if (readOnlyTemplate) {\n            loadReadOnly();"),
                "只读模板必须绕开会迁移 NBT 和写统计字段的常规 load()");

        int start = source.indexOf("private void loadReadOnly()");
        int end = source.indexOf("private void load()", start + 1);
        assertTrue(start >= 0 && end > start, "必须提供独立的只读加载路径");
        String readOnlyBody = source.substring(start, end);
        assertFalse(readOnlyBody.contains("putUUID("), "只读加载不得写入 UUID");
        assertFalse(readOnlyBody.contains("tag.remove("), "只读加载不得迁移或删除原始 NBT");
        assertFalse(readOnlyBody.contains("persist("), "只读加载不得写入 SDA 后端");
        assertFalse(readOnlyBody.contains("writeLightweightStats"), "只读加载不得回写轻量统计字段");
    }
}
