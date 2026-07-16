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
        assertTrue(source.contains("types.putIfAbsent(type.registryName, type)"));
        assertTrue(source.contains("PatternRecipeExecutionGuard.isAuxiliaryIORecipeTypeId(type.registryName)"));
    }
}
