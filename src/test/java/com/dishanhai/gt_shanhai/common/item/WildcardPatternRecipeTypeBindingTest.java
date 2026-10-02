package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WildcardPatternRecipeTypeBindingTest {

    private static final Path BINDING = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "item", "WildcardPatternRecipeTypeBinding.java");

    @Test
    void bindingCopiesStacksAndUsesTheSharedRecipeTypeTag() throws IOException {
        String source = Files.readString(BINDING);

        assertTrue(source.contains("ItemStack result = source.copy()"));
        assertTrue(source.contains("PatternRecipeTypeHelper.TAG_RECIPE_TYPE, recipeTypeId"));
        assertTrue(source.contains("tag.remove(PatternRecipeTypeHelper.TAG_RECIPE_TYPE)"));
        assertTrue(source.contains("if (tag.isEmpty()) result.setTag(null)"));
    }

    @Test
    void bindingFiltersExpandedPatternsAndHostTypes() throws IOException {
        String source = Files.readString(BINDING);

        assertTrue(source.contains("findMatchingRecipeForPattern(")
                && source.contains("pattern.getSparseInputs(), pattern.getSparseOutputs(), recipeTypeId"));
        assertTrue(source.contains("machine.getRecipeTypes()"));
        assertTrue(source.contains("target.putIfAbsent(current.registryName, current)"));
        assertTrue(source.contains("PatternRecipeExecutionGuard.isAuxiliaryIORecipeTypeId(current.registryName)"));
        assertTrue(source.contains("getMultiRecipeType"));
        assertTrue(source.contains("getTypeList"));
        assertTrue(source.contains("getRecipeTypeNameSet"),
                "原初模块公开的完整配方类型集合必须参与星律目标匹配");
        assertTrue(source.contains("getAllSelectableRecipeTypes"),
                "选择集机器的完整类型列表必须作为运行时空集合的兜底");
        assertTrue(source.contains("addNamedRecipeTypes(types, machine, \"getRecipeTypeNameSet\")"),
                "完整配方类型集合不能只在 getRecipeTypes() 为空时读取");
    }
}
