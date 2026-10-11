package com.dishanhai.gt_shanhai.common.recipe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeOverrideSupersedeTest {

    @Test
    void unchangedDatapackKeepsTheAnnotation() {
        assertFalse(RecipeRebuildService.shouldRetireOverride(
                false, "base", true, true, "base", "legacy"));
    }

    @Test
    void legacyFingerprintStillCountsAsTheSameBase() {
        assertFalse(RecipeRebuildService.shouldRetireOverride(
                false, "legacy", true, true, "current", "legacy"));
    }

    @Test
    void updatedJarRetiresTheOldAnnotation() {
        assertTrue(RecipeRebuildService.shouldRetireOverride(
                false, "old-base", true, true, "new-jar", "new-legacy"));
    }

    @Test
    void emptyFingerprintIsKept() {
        assertFalse(RecipeRebuildService.shouldRetireOverride(
                false, "", true, true, "new-jar", "new-legacy"));
    }

    @Test
    void annotationWithoutADatapackSourceIsKept() {
        assertFalse(RecipeRebuildService.shouldRetireOverride(
                false, "old-base", false, false, null, null));
    }

    @Test
    void createdAnnotationRetiresOnceTheJarDefinesThatId() {
        assertTrue(RecipeRebuildService.shouldRetireOverride(
                true, "created", true, false, null, null));
        assertFalse(RecipeRebuildService.shouldRetireOverride(
                true, "created", false, false, null, null));
    }
}
