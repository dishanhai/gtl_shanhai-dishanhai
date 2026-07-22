package com.dishanhai.gt_shanhai.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleLevelConditionTest {

    private static final Path CONDITION_SOURCE = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "api", "ModuleLevelCondition.java");

    @Test
    void higherLevelsDoubleEquivalentCountPerLevel() {
        assertEquals(1L, ModuleLevelEquivalence.calculateEquivalentCount(4, 1, 4));
        assertEquals(2L, ModuleLevelEquivalence.calculateEquivalentCount(5, 1, 4));
        assertEquals(4L, ModuleLevelEquivalence.calculateEquivalentCount(6, 1, 4));
        assertEquals(8L, ModuleLevelEquivalence.calculateEquivalentCount(7, 1, 4));
    }

    @Test
    void aFourLevelLeadIgnoresRequiredQuantityAtAnyTier() {
        assertTrue(ModuleLevelEquivalence.isRequirementSatisfied(8, 1, 4, Integer.MAX_VALUE));
        assertTrue(ModuleLevelEquivalence.isRequirementSatisfied(13, 1, 9, Integer.MAX_VALUE));
        assertTrue(ModuleLevelEquivalence.isRequirementSatisfied(15, 1, 4, Integer.MAX_VALUE));
        assertFalse(ModuleLevelEquivalence.isRequirementSatisfied(10, 1, 9, 3));
        assertTrue(ModuleLevelEquivalence.isRequirementSatisfied(10, 2, 9, 3));
    }

    @Test
    void insufficientAndOverflowingLevelsAreHandledSafely() {
        assertEquals(0L, ModuleLevelEquivalence.calculateEquivalentCount(3, 64, 4));
        assertFalse(ModuleLevelEquivalence.isRequirementSatisfied(3, 64, 4, 1));
        assertEquals(Long.MAX_VALUE,
                ModuleLevelEquivalence.calculateEquivalentCount(80, Integer.MAX_VALUE, 4));
    }

    @Test
    void registryUsesExactIdsAndCanBeClearedOnReload() throws IOException {
        String source = Files.readString(CONDITION_SOURCE);

        assertTrue(source.contains("return REQUIREMENTS.get(recipeId);"));
        assertTrue(source.contains("public static void clearRequirements()"));
        assertTrue(source.contains("REQUIREMENTS.clear();"));
        assertFalse(source.contains("recipeId.contains(entry.getKey())"));
        assertFalse(source.contains("lastSegment("));
    }
}
