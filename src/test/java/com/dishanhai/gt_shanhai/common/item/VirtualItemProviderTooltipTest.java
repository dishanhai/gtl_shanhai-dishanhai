package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualItemProviderTooltipTest {

    private static final Path ITEM = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "VirtualItemProviderItem.java");

    @Test
    void boundTooltipAlwaysPrefixesTargetNameWithStoredCount() throws Exception {
        String source = Files.readString(ITEM);

        assertTrue(source.contains("Component.literal(target.getCount() + \"x \")"),
                "绑定提示必须在目标名称前显示保存的目标数量，包括数量为 1 时");
        assertTrue(source.indexOf("Component.literal(target.getCount() + \"x \")")
                        < source.indexOf("target.getHoverName()"),
                "绑定数量必须显示在目标物品名称之前");
    }
}
