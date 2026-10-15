package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单配方切换机器（大型切割机同时拥有切割机和车床）停在其中一个类型时，
 * 虚拟执行链仍必须能跑宿主拥有的另一个类型。
 */
class InactiveHostRecipeTypeVirtualExecutionSourceTest {

    private static final Path SEARCH_HELPER = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "item", "RecipeTypePatternSearchHelper.java");
    private static final Path NATIVE_FIND = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "NativeVirtualFindHandleRecipeMixin.java");
    private static final Path LOOKUP = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "InactiveHostRecipeTypeLookupMixin.java");
    private static final Path TYPE_MIXIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "mixin", "VirtualOwnedRecipeTypeMixin.java");
    private static final Path MIXIN_JSON = Path.of("src", "main", "resources", "gt_shanhai.mixin.json");

    @Test
    void nativeVirtualChainKeepsInactiveHostCachedRecipes() throws IOException {
        String source = Files.readString(SEARCH_HELPER);
        int methodStart = source.indexOf("public static Set<GTRecipe> collectNativeVirtualRecipes");
        int methodEnd = source.indexOf("private static void appendInactiveHostCachedRecipes", methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart);
        String method = source.substring(methodStart, methodEnd);
        assertTrue(method.contains("appendInactiveHostCachedRecipes(machine, result)"),
                "虚拟执行链必须补上宿主拥有但当前没选中的已缓存样板配方");
        assertFalse(method.contains("if (!allowCheatVirtualExecution()) return result;"),
                "未选中类型的正常虚拟执行不能被作弊开关整段关掉");
    }

    @Test
    void setupReportsOwnedInactiveTypeWithoutCheatBypass() throws IOException {
        String source = Files.readString(NATIVE_FIND);
        assertTrue(source.contains("beginOwnedInactiveType"),
                "启动未选中类型时必须临时回报该配方类型");
        assertTrue(source.contains("endOwnedInactiveType"),
                "setup 结束后必须清掉临时配方类型");
        assertTrue(source.contains("if (bypassHostLimits)"),
                "整段跳过 beforeWorking 仍然只能走作弊开关");
        assertFalse(source.contains("beginOwnedInactiveType(ownedInactive);\n            NativeVirtualSetupState.beginVirtualExecution();"),
                "未选中类型的模式对齐不能顺手打开作弊破限");
    }

    @Test
    void lookupAppendsInactiveHostTypesAfterGtlCoreFilter() throws IOException {
        String lookup = Files.readString(LOOKUP);
        String typeMixin = Files.readString(TYPE_MIXIN);
        String json = Files.readString(MIXIN_JSON);

        assertTrue(lookup.contains("priority = 1500"),
                "必须落在 GTLCore 改写后的 getRecipeIterator 上");
        assertTrue(lookup.contains("gtShanhai$sameRecipeType(this.recipeType, recipe.recipeType)) continue"),
                "当前查找类型已由 GTLCore 放入候选，不能重复追加");
        assertTrue(lookup.contains("setAdditionalRecipes"),
                "未选中但宿主拥有的样板缓存必须并进候选迭代器");
        assertTrue(lookup.contains("SelectableRecipeTypeSetMachine"),
                "选择集机器自己会逐个选中类型搜索，不能在这里再放开未选类型");
        assertTrue(typeMixin.contains("NativeVirtualSetupState.ownedInactiveType()"),
                "getRecipeType 必须在虚拟执行这一轮回报未选中的宿主类型");
        assertTrue(json.contains("InactiveHostRecipeTypeLookupMixin"));
        assertTrue(json.contains("VirtualOwnedRecipeTypeMixin"));
    }
}
