package com.dishanhai.gt_shanhai.common.recipe;

import org.junit.jupiter.api.Test;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeBase;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeFingerprint;

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
    void v3SnapshotStillMatchesAfterDigestUpgrade() {
        assertFalse(RecipeRebuildService.shouldRetireOverride(
                false, "v3-snapshot", true, true, "v4-digest", "legacy", "v3-snapshot"));
        assertTrue(RecipeRebuildService.shouldRetireOverride(
                false, "v3-old", true, true, "v4-digest", "legacy", "v3-current"));
    }

    @Test
    void realV3SnapshotSurvivesUpgradeButChangedDatapackStillRetiresIt() {
        ShanhaiRecipeBase original = ShanhaiRecipeBase.simple("gtceu:assembler", "gtceu:test", 20, 0, 1);
        String stored = ShanhaiRecipeFingerprint.legacyV3Fingerprint(original);
        assertFalse(RecipeRebuildService.shouldRetireOverride(false, stored, true, true,
                ShanhaiRecipeFingerprint.of(original),
                ShanhaiRecipeFingerprint.legacySnapshotFingerprint(original), stored));
        ShanhaiRecipeBase changed = original.withDuration(40);
        assertTrue(RecipeRebuildService.shouldRetireOverride(false, stored, true, true,
                ShanhaiRecipeFingerprint.of(changed),
                ShanhaiRecipeFingerprint.legacySnapshotFingerprint(changed),
                ShanhaiRecipeFingerprint.legacyV3Fingerprint(changed)));
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
