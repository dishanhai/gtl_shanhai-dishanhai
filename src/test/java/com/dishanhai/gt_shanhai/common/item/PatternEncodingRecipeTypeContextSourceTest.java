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
    private static final Path ENCODING_HELPER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "VirtualPatternEncodingHelper.java");
    private static final Path SELECTED_RECIPE_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "JEISelectedRecipeContextMixin.java");
    private static final Path WRAP_MENU_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "PatternWrapControlMenuMixin.java");

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

    @Test
    void carriesAndValidatesTheExactJeiSelectedRecipe() throws Exception {
        String selectedMixin = Files.readString(SELECTED_RECIPE_MIXIN);
        String wrapMenuMixin = Files.readString(WRAP_MENU_MIXIN);
        String helper = Files.readString(HELPER);
        String encodingHelper = Files.readString(ENCODING_HELPER);
        String config = Files.readString(CONFIG);

        assertTrue(selectedMixin.contains("JEISelectedRecipeContextMixin"));
        assertTrue(selectedMixin.contains("recipe.id.toString()"),
                "JEI 传输必须同步精确 GTRecipe ID，而不是只同步 recipe type");
        assertTrue(selectedMixin.contains("GTRecipeWrapper"),
                "GTCEu JEI 实际传输的是 GTRecipeWrapper，必须提取 wrapper.recipe");
        assertTrue(selectedMixin.contains("return wrapper.recipe"),
                "不能把 GTRecipeWrapper 当作裸 GTRecipe 而丢弃精确配方");
        assertTrue(selectedMixin.contains("gtShanhai$clearSelectedRecipeAfterFailedTransfer"),
                "JEI 传输失败后必须清除旧配方 ID，避免下次编码复用过期上下文");
        assertTrue(wrapMenuMixin.contains("gtShanhaiRememberSelectedRecipe"),
                "样板终端必须注册服务端动作接收精确配方 ID");
        assertTrue(helper.contains("currentSelectedEncodingRecipe()"),
                "编码反查必须优先读取 JEI 选中的配方上下文");
        assertTrue(encodingHelper.contains("falling back to unscoped exact lookup"),
                "选中配方失配后不能继续沿用旧配方类型猜测");
        assertTrue(encodingHelper.contains("matchesRecipeInputsAtMultiplier(selectedRecipe"),
                "复用 JEI 配方前必须重新验证当前样板输入和输出");
        assertTrue(helper.contains("// JEI 传输后槽位可能被玩家改动"),
                "选中配方失配时必须停止写入旧配方类型");
        assertTrue(config.contains("JEISelectedRecipeContextMixin"));
    }

    private static final class FakeGtlCoreMenu {

        @SuppressWarnings("unused")
        private final ResourceLocation gTLCore$pendingQuickUploadRecipeTypeId =
                new ResourceLocation("gtceu", "nano_forge");
    }
}
