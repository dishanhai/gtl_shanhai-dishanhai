package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.resources.ResourceLocation;

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

        assertTrue(source.contains("gtShanhai$readPendingRecipeTypeId(this)"),
                "编码入口必须直接读取 GTLCore 已同步到服务端菜单的 pending 配方类型");
        assertTrue(source.contains("gTLCore$pendingQuickUploadRecipeTypeId"),
                "不能再依赖对 GTLCore @Unique setter 的跨 Mixin 注入捕获类型");
        assertTrue(source.contains("method = \"encodeProcessingPattern\""));
        assertTrue(source.contains("PatternRecipeTypeHelper.pushEncodingRecipeType"));
        assertTrue(source.contains("PatternRecipeTypeHelper.popEncodingRecipeType"));
        assertTrue(config.contains("PatternEncodingRecipeTypeContextMixin"));
        assertTrue(helper.contains("writeAuthoritativeRecipeType(stack, encodingRecipeType)"),
                "编码返回时必须直接写入同一类型上下文，不能再次全局反推类型");
        assertTrue(helper.indexOf("currentEncodingRecipeTypeId()")
                        < helper.indexOf("findMatchingRecipeForPattern(inputs, outputs)"),
                "类型上下文写入必须先于无类型全局兜底");
    }

    @Test
    void readsPendingGtlCoreRecipeTypeDirectlyFromServerMenu() throws Exception {
        Class<?> mixinClass = Class.forName(
                "com.dishanhai.gt_shanhai.mixin.PatternEncodingRecipeTypeContextMixin");
        Method reader = assertDoesNotThrow(
                () -> mixinClass.getDeclaredMethod("gtShanhai$readPendingRecipeTypeId", Object.class),
                "必须提供可缓存的 GTLCore pending 配方类型读取器");
        reader.setAccessible(true);

        assertEquals("gtceu:nano_forge", reader.invoke(null, new FakeGtlCoreMenu()));
    }

    private static final class FakeGtlCoreMenu {

        @SuppressWarnings("unused")
        private final ResourceLocation gTLCore$pendingQuickUploadRecipeTypeId =
                new ResourceLocation("gtceu", "nano_forge");
    }
}
