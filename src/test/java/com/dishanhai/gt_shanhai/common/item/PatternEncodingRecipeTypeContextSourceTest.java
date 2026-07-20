package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class PatternEncodingRecipeTypeContextSourceTest {

    private static final Path MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "PatternEncodingRecipeTypeContextMixin.java");
    private static final Path CONFIG = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");
    private static final Path HELPER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "PatternRecipeTypeHelper.java");

    @Test
    void jeiSelectedRecipeTypeSurvivesUntilServerPatternEncoding() throws Exception {
        String source = Files.readString(MIXIN);
        String config = Files.readString(CONFIG);
        String helper = Files.readString(HELPER);

        assertTrue(source.contains("priority = 800"),
                "类型桥必须晚于 GTLCore priority=900 菜单 Mixin 应用");
        assertTrue(source.contains("gTLCore$setQuickUploadRecipeType"),
                "必须捕获 JEI/REI 选中的配方类型");
        assertTrue(source.contains("gTLCore$setQuickUploadRecipeTypeFromClient"),
                "必须捕获服务端 client action 实际落地的类型");
        assertTrue(source.contains("method = \"encodeProcessingPattern\""));
        assertTrue(source.contains("PatternRecipeTypeHelper.pushEncodingRecipeType"));
        assertTrue(source.contains("PatternRecipeTypeHelper.popEncodingRecipeType"));
        assertTrue(source.contains("method = \"encode\""));
        assertTrue(source.contains("gtShanhai$encodingRecipeTypeId = \"\""));
        assertTrue(config.contains("PatternEncodingRecipeTypeContextMixin"));
        assertTrue(helper.contains("writeAuthoritativeRecipeType(stack, encodingRecipeType)"),
                "编码返回时必须直接写入同一类型上下文，不能再次全局反推类型");
        assertTrue(helper.indexOf("currentEncodingRecipeTypeId()")
                        < helper.indexOf("findMatchingRecipeForPattern(inputs, outputs)"),
                "类型上下文写入必须先于无类型全局兜底");
    }
}
