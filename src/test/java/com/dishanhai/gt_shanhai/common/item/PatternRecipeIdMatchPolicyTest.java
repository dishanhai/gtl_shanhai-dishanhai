package com.dishanhai.gt_shanhai.common.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternRecipeIdMatchPolicyTest {

    @Test
    void matchesExactTypeNamespaceAndPathSuffix() {
        assertTrue(PatternRecipeIdMatchPolicy.matches(
                "gtceu:macerator", "gt_shanhai:macerator/essence",
                "gtceu:macerator", "gt_shanhai:macerator/essence"));
    }

    @Test
    void rejectsAnotherNamespaceEvenWhenTypeAndSuffixMatch() {
        assertFalse(PatternRecipeIdMatchPolicy.matches(
                "gtceu:macerator", "gt_shanhai:macerator/essence",
                "gtceu:macerator", "kubejs:macerator/essence"));
    }

    @Test
    void rejectsAnotherRecipeTypeOrSuffix() {
        assertFalse(PatternRecipeIdMatchPolicy.matches(
                "gtceu:macerator", "gt_shanhai:macerator/essence",
                "gtceu:cutter", "gt_shanhai:macerator/essence"));
        assertFalse(PatternRecipeIdMatchPolicy.matches(
                "gtceu:macerator", "gt_shanhai:macerator/essence",
                "gtceu:macerator", "gt_shanhai:macerator/essence_alt"));
    }

    @Test
    void rejectsMissingIdsAndIdsWithoutTheRecipeTypePathPrefix() {
        assertFalse(PatternRecipeIdMatchPolicy.matches(
                "gtceu:macerator", "", "gtceu:macerator", "gt_shanhai:macerator/essence"));
        assertFalse(PatternRecipeIdMatchPolicy.matches(
                "gtceu:macerator", "gt_shanhai:essence",
                "gtceu:macerator", "gt_shanhai:essence"));
        assertTrue(PatternRecipeIdMatchPolicy.hasTypePrefix(
                "gtceu:macerator", "gt_shanhai:macerator/essence"));
        assertFalse(PatternRecipeIdMatchPolicy.hasTypePrefix(
                "gtceu:cutter", "gt_shanhai:macerator/essence"));
    }
}
